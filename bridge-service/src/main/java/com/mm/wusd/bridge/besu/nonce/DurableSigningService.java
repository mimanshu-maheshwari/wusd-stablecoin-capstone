package com.mm.wusd.bridge.besu.nonce;

import com.mm.wusd.bridge.besu.DemoTransactionSigner;
import com.mm.wusd.bridge.besu.PreparedTransaction;
import com.mm.wusd.bridge.outbox.MintWorkClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

@Service
public class DurableSigningService {

  private final JdbcTemplate jdbc;

  public DurableSigningService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public SignedAttempt signFirst(
    MintWorkClaim claim, String privateKey, String recipient,
    BigInteger amountWei, BigInteger gasPriceWei
  ) {

    requireClaim(claim);

    NonceReservation reservation = lockReservation(claim.id());

    List<SignedAttempt> attempts = attempts(reservation.id());

    // Recovered worker gets the SAME signed bytes.
    if (!attempts.isEmpty()) {

      SignedAttempt existing = attempts.getFirst();

      String derivedSigner = Credentials.create(privateKey).getAddress();

      if (!derivedSigner.equalsIgnoreCase(reservation.signerAddress())) {

        throw new IllegalStateException(
          "Signing key does not match reservation");
      }

      if (
        !existing.recipient().equalsIgnoreCase(recipient)
        || !existing.valueWei().equals(amountWei)
        || !"0x".equals(existing.calldata())
      ) {
        throw new IllegalStateException(
          "Retry attempts to change transaction intent");
      }

      return existing;
    }

    if (!"RESERVED".equals(reservation.status())) {
      throw new IllegalStateException(
        "Reservation cannot be signed in state " + reservation.status());
    }

    PreparedTransaction prepared = DemoTransactionSigner.prepareTransfer(
      privateKey, reservation.chainId(), reservation.nonce(), gasPriceWei,
      recipient, amountWei
    );

    if (!prepared.sender().equalsIgnoreCase(reservation.signerAddress())) {
      throw new IllegalStateException(
        "Private key does not match reserved signer");
    }

    SignedAttempt signed = jdbc.queryForObject(
      """
        INSERT INTO signed_transaction_attempt (
            reservation_id,
            attempt_number,
            transaction_hash,
            signed_raw_tx,
            recipient,
            value_wei,
            gas_limit,
            gas_price_wei,
            calldata
        )
        VALUES (?, 1, ?, ?, ?, ?, ?, ?, '0x')
        RETURNING *
        """, this::mapAttempt, reservation.id(),
      prepared.transactionHash().toLowerCase(Locale.ROOT), prepared.signedHex(),
      recipient.toLowerCase(Locale.ROOT), new BigDecimal(amountWei),
      BigDecimal.valueOf(21_000), new BigDecimal(gasPriceWei)
    );

    jdbc.update(
      """
        UPDATE signer_nonce_reservation
        SET status = 'SIGNED',
            updated_at = now()
        WHERE id = ?
          AND status = 'RESERVED'
        """, reservation.id()
    );

    return signed;
  }

  private void requireClaim(MintWorkClaim claim) {
    List<Long> found = jdbc.query(
      """
        SELECT id
        FROM mint_outbox
        WHERE id = ?
          AND status = 'CLAIMED'
          AND claimed_by = ?
          AND lease_token = ?
          AND lease_expires_at > now()
        FOR UPDATE
        """, (rs, row) -> rs.getLong(1), claim.id(), claim.claimedBy(),
      claim.leaseToken()
    );

    if (found.isEmpty()) {
      throw new IllegalStateException("Stale or expired outbox claim");
    }
  }

  private NonceReservation lockReservation(long outboxId) {

    return jdbc.query(
      """
        SELECT *
        FROM signer_nonce_reservation
        WHERE outbox_id = ?
        FOR UPDATE
        """, (rs, row) -> new NonceReservation(
        rs.getLong("id"), rs.getLong("outbox_id"), rs.getLong("chain_id"),
        rs.getString("signer_address"),
        rs.getBigDecimal("account_nonce").toBigIntegerExact(),
        rs.getString("status")
      ), outboxId
    ).stream().findFirst().orElseThrow(() -> new IllegalStateException(
      "No nonce reservation " + "exists for work"));
  }

  private List<SignedAttempt> attempts(long reservationId) {

    return jdbc.query(
      """
        SELECT *
        FROM signed_transaction_attempt
        WHERE reservation_id = ?
        ORDER BY attempt_number DESC
        """, this::mapAttempt, reservationId
    );
  }

  private SignedAttempt mapAttempt(ResultSet rs, int row) throws SQLException {

    return new SignedAttempt(
      rs.getLong("id"), rs.getLong("reservation_id"),
      rs.getInt("attempt_number"), rs.getString("transaction_hash"),
      rs.getString("signed_raw_tx"),
      rs.getBigDecimal("gas_price_wei").toBigIntegerExact(),
      rs.getString("recipient"),
      rs.getBigDecimal("value_wei").toBigIntegerExact(),
      rs.getBigDecimal("gas_limit").toBigIntegerExact(),
      rs.getString("calldata"), rs.getString("status")
    );
  }

  @Transactional
  public SignedAttempt prepareReplacement(
    MintWorkClaim claim,
    String privateKey,
    BigInteger newGasPriceWei
  ) {

    requireClaim(claim);

    NonceReservation reservation = lockReservation(claim.id());

    if ("INCLUDED_SUCCESS".equals(
      reservation.status()) || "INCLUDED_REVERTED".equals(
      reservation.status()) || "RECONCILIATION_REQUIRED".equals(
      reservation.status())) {
      throw new IllegalStateException("Terminal or unresolved reservation");
    }

    List<SignedAttempt> prior = attempts(reservation.id());

    if (prior.isEmpty()) {
      throw new IllegalStateException("No original signed transaction");
    }

    SignedAttempt previous = prior.getFirst();

    if (!"0x".equals(previous.calldata())) {
      throw new IllegalStateException(
        "This demo replacement supports ETH transfers only");
    }

    BigInteger oldPrice = previous.gasPriceWei();

    if (newGasPriceWei == null || newGasPriceWei.signum() <= 0 || newGasPriceWei.compareTo(
      oldPrice) <= 0 || newGasPriceWei.multiply(
      BigInteger.valueOf(100)).compareTo(
      oldPrice.multiply(BigInteger.valueOf(115))) < 0) {
      throw new IllegalArgumentException(
        "Replacement must increase gas price by at least 15%");
    }

    PreparedTransaction replacement = DemoTransactionSigner.prepareTransfer(
      privateKey, reservation.chainId(), reservation.nonce(), newGasPriceWei,
      previous.recipient(), previous.valueWei()
    );

    if (!replacement.sender().equalsIgnoreCase(reservation.signerAddress())) {

      throw new IllegalStateException("Incorrect signing key");
    }

    SignedAttempt signed = jdbc.queryForObject(
      """
        INSERT INTO signed_transaction_attempt (
            reservation_id,
            attempt_number,
            transaction_hash,
            signed_raw_tx,
            recipient,
            value_wei,
            gas_limit,
            gas_price_wei,
            calldata
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING *
        """, this::mapAttempt, reservation.id(), previous.attemptNumber() + 1,
      replacement.transactionHash().toLowerCase(Locale.ROOT),
      replacement.signedHex(), previous.recipient(),
      new BigDecimal(previous.valueWei()), new BigDecimal(previous.gasLimit()),
      new BigDecimal(newGasPriceWei), previous.calldata()
    );

    return signed;
  }

}
