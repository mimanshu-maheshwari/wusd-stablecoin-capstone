package com.mm.wusd.bridge.besu.nonce;

import com.mm.wusd.bridge.besu.BesuRpcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.List;

@Service
public class NonceReconciler {

  private final JdbcTemplate jdbc;
  private final BesuRpcClient rpc;
  private final TransactionTemplate transactions;

  public NonceReconciler(
    JdbcTemplate jdbc, BesuRpcClient rpc,
    PlatformTransactionManager transactionManager
  ) {

    this.jdbc = jdbc;
    this.rpc = rpc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  // External RPC happens outside a DB transaction.
  public String reconcile(long reservationId) {

    ReservationInfo reservation = loadReservation(reservationId);

    List<AttemptInfo> attempts = jdbc.query(
      """
        SELECT id, transaction_hash
        FROM signed_transaction_attempt
        WHERE reservation_id = ?
        ORDER BY attempt_number DESC
        """, (rs, row) -> new AttemptInfo(
        rs.getLong("id"),
        rs.getString("transaction_hash")
      ), reservationId
    );

    if (attempts.isEmpty()) {
      return "RESERVED_NOT_SIGNED";
    }

    boolean anyPending = false;

    for (AttemptInfo attempt : attempts) {

      JsonNode receipt = rpc.receipt(attempt.hash());

      if (!receipt.isNull()) {

        String blockNumber = receipt.path("blockNumber").asText();

        String blockHash = receipt.path("blockHash").asText();

        JsonNode block = rpc.blockByNumber(blockNumber);

        if (block.isNull() || !blockHash.equalsIgnoreCase(
          block.path("hash").asText())) {
          return "RECHECK_CANONICAL_BLOCK";
        }

        String executionStatus = receipt.path("status").asText();
        if (!"0x1".equals(executionStatus) && !"0x0".equals(executionStatus)) {
          return "UNKNOWN_RECEIPT_STATUS";
        }

        boolean successful = "0x1".equals(executionStatus);
        recordIncluded(reservationId, attempt.id(), successful);
        return successful ? "TRANSACTION_EXECUTED" : "TRANSACTION_REVERTED";
      }

      JsonNode pending = rpc.transactionByHash(attempt.hash());

      if (!pending.isNull()) {
        anyPending = true;
      }
    }

    if (anyPending) {
      return "KNOWN_TRANSACTION_PENDING";
    }

    BigInteger confirmedNonce = rpc.transactionCount(
      reservation.signerAddress(), "latest");

    if (confirmedNonce.compareTo(reservation.nonce()) > 0) {
      markNeedsInvestigation(reservationId);
      return "NONCE_CONSUMED_BUT_ATTEMPT_UNKNOWN";
    }

    return "NOT_VISIBLE_RECHECK_OR_REBROADCAST";
  }

  public void recordIncluded(
    long reservationId, long attemptId, boolean success) {
    transactions.executeWithoutResult(tx -> {

      // Lock the reservation first, consistently
      // with the other nonce-management operations.
      List<String> states = jdbc.query(
        """
          SELECT status
          FROM signer_nonce_reservation
          WHERE id = ?
          FOR UPDATE
          """, (rs, row) -> rs.getString("status"), reservationId
      );

      if (states.isEmpty()) {
        throw new IllegalStateException("Reservation not found");
      }

      List<Long> attempts = jdbc.query(
        """
          SELECT id
          FROM signed_transaction_attempt
          WHERE id = ?
            AND reservation_id = ?
          FOR UPDATE
          """, (rs, row) -> rs.getLong(1), attemptId, reservationId
      );

      if (attempts.isEmpty()) {
        throw new IllegalStateException(
          "Attempt does not belong to reservation");
      }

      String terminal = success ? "INCLUDED_SUCCESS" : "INCLUDED_REVERTED";

      if (states.getFirst().startsWith("INCLUDED_")) {
        if (!states.getFirst().equals(terminal)) {
          throw new IllegalStateException("Conflicting terminal observation");
        }
        return;
      }

      jdbc.update(
        """
          UPDATE signed_transaction_attempt
          SET status = ?
          WHERE id = ?
          """, terminal, attemptId
      );

      jdbc.update(
        """
          UPDATE signer_nonce_reservation
          SET status = ?,
              updated_at = now()
          WHERE id = ?
          """, terminal, reservationId
      );
    });
  }

  public void markNeedsInvestigation(long reservationId) {
    transactions.executeWithoutResult(tx -> {
      jdbc.update(
        """
          UPDATE signer_nonce_reservation
          SET status = 'RECONCILIATION_REQUIRED',
              updated_at = now()
          WHERE id = ?
            AND status NOT IN (
                'INCLUDED_SUCCESS',
                'INCLUDED_REVERTED'
            )
          """, reservationId
      );
    });
  }

  private ReservationInfo loadReservation(long id) {

    return jdbc.queryForObject(
      """
        SELECT signer_address, account_nonce
        FROM signer_nonce_reservation
        WHERE id = ?
        """,
      (rs, row) -> new ReservationInfo(
        rs.getString("signer_address"),
        rs.getBigDecimal("account_nonce").toBigIntegerExact()
      ), id
    );
  }

  private record ReservationInfo(
    String signerAddress, BigInteger nonce
  ) {
  }

  private record AttemptInfo(
    long id, String hash
  ) {
  }
}

