package com.mm.wusd.bridge.besu.nonce;

import com.mm.wusd.bridge.outbox.MintWorkClaim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class AttemptStateRepository {

  private final JdbcTemplate jdbc;

  public AttemptStateRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public SignedAttempt prepareBroadcast(
    MintWorkClaim claim,
    long attemptId) {

    List<Long> validClaim = jdbc.query(
      """
      SELECT id
      FROM mint_outbox
      WHERE id = ?
        AND status = 'CLAIMED'
        AND claimed_by = ?
        AND lease_token = ?
        AND lease_expires_at > now()
      FOR UPDATE
      """,
      (rs, row) -> rs.getLong(1),
      claim.id(),
      claim.claimedBy(),
      claim.leaseToken()
    );

    if (validClaim.isEmpty()) {
      throw new IllegalStateException(
        "Worker lost its outbox lease"
      );
    }

    List<SignedAttempt> attempts = jdbc.query(
      """
      SELECT a.*
      FROM signed_transaction_attempt a
      JOIN signer_nonce_reservation r
        ON r.id = a.reservation_id
      WHERE a.id = ?
        AND r.outbox_id = ?
        AND r.status NOT IN (
            'INCLUDED_SUCCESS',
            'INCLUDED_REVERTED',
            'RECONCILIATION_REQUIRED'
        )
      FOR UPDATE OF a
      """,
      (rs, row) -> mapAttempt(rs),
      attemptId,
      claim.id()
    );

    if (attempts.isEmpty()) {
      throw new IllegalStateException(
        "Attempt unavailable or reservation terminal"
      );
    }

    SignedAttempt attempt = attempts.getFirst();

    jdbc.update(
      """
      UPDATE signed_transaction_attempt
      SET status = 'BROADCAST_UNKNOWN'
      WHERE id = ?
        AND status IN (
            'SIGNED',
            'SUBMITTED',
            'BROADCAST_UNKNOWN'
        )
      """,
      attemptId
    );

    jdbc.update(
      """
      UPDATE signer_nonce_reservation
      SET status = 'BROADCAST_UNKNOWN',
          updated_at = now()
      WHERE id = ?
        AND status IN (
            'SIGNED',
            'SUBMITTED',
            'BROADCAST_UNKNOWN'
        )
      """,
      attempt.reservationId()
    );

    return attempt;
  }

  @Transactional
  public void markAccepted(long attemptId) {

    jdbc.update(
      """
      UPDATE signed_transaction_attempt
      SET status = 'SUBMITTED'
      WHERE id = ?
        AND status = 'BROADCAST_UNKNOWN'
      """,
      attemptId
    );

    jdbc.update(
      """
      UPDATE signer_nonce_reservation r
      SET status = 'SUBMITTED',
          updated_at = now()
      FROM signed_transaction_attempt a
      WHERE a.id = ?
        AND a.reservation_id = r.id
        AND r.status = 'BROADCAST_UNKNOWN'
      """,
      attemptId
    );
  }

  private SignedAttempt mapAttempt(
    java.sql.ResultSet rs)
    throws java.sql.SQLException {

    return new SignedAttempt(
      rs.getLong("id"),
      rs.getLong("reservation_id"),
      rs.getInt("attempt_number"),
      rs.getString("transaction_hash"),
      rs.getString("signed_raw_tx"),
      rs.getBigDecimal("gas_price_wei")
        .toBigIntegerExact(),
      rs.getString("recipient"),
      rs.getBigDecimal("value_wei")
        .toBigIntegerExact(),
      rs.getBigDecimal("gas_limit")
        .toBigIntegerExact(),
      rs.getString("calldata"),
      rs.getString("status")
    );
  }
}

