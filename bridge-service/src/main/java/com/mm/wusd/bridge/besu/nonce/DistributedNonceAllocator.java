package com.mm.wusd.bridge.besu.nonce;

import com.mm.wusd.bridge.outbox.MintWorkClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

@Service
public class DistributedNonceAllocator {

  private final JdbcTemplate jdbc;

  public DistributedNonceAllocator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public NonceReservation reserve(
    MintWorkClaim claim,
    long chainId,
    String signerAddress) {

    String signer = signerAddress.toLowerCase(Locale.ROOT);

    requireCurrentClaim(claim);

    List<BigDecimal> cursor = jdbc.query(
      """
        SELECT next_nonce
        FROM signer_nonce_cursor
        WHERE chain_id = ?
          AND signer_address = ?
        FOR UPDATE
        """,
      (rs, row) -> rs.getBigDecimal("next_nonce"),
      chainId,
      signer
    );

    if (cursor.isEmpty()) {
      throw new IllegalStateException(
        "Signer cursor is not initialized"
      );
    }

    // Check AFTER locking the signer cursor.
    // Concurrent retries serialize on that lock.
    List<NonceReservation> existing =
      jdbc.query(
        """
          SELECT *
          FROM signer_nonce_reservation
          WHERE outbox_id = ?
          """,
        this::map,
        claim.id()
      );

    if (!existing.isEmpty()) {
      NonceReservation reservation = existing.getFirst();

      if (reservation.chainId() != chainId ||
        !reservation.signerAddress().equals(signer)) {

        throw new IllegalStateException(
          "Existing reservation belongs to another signer"
        );
      }

      return reservation;
    }

    BigInteger nonce =
      cursor.getFirst().toBigIntegerExact();

    jdbc.update(
      """
        UPDATE signer_nonce_cursor
        SET next_nonce = next_nonce + 1,
            updated_at = now()
        WHERE chain_id = ?
          AND signer_address = ?
        """,
      chainId,
      signer
    );

    return jdbc.queryForObject(
      """
        INSERT INTO signer_nonce_reservation (
            chain_id,
            signer_address,
            account_nonce,
            outbox_id,
            status
        )
        VALUES (?, ?, ?, ?, 'RESERVED')
        RETURNING *
        """,
      this::map,
      chainId,
      signer,
      new BigDecimal(nonce),
      claim.id()
    );
  }

  private void requireCurrentClaim(MintWorkClaim claim) {

    List<Long> matches = jdbc.query(
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

    if (matches.isEmpty()) {
      throw new IllegalStateException(
        "Outbox lease is missing, expired, or fenced"
      );
    }
  }

  private NonceReservation map(
    java.sql.ResultSet rs,
    int row) throws java.sql.SQLException {

    return new NonceReservation(
      rs.getLong("id"),
      rs.getLong("outbox_id"),
      rs.getLong("chain_id"),
      rs.getString("signer_address"),
      rs.getBigDecimal("account_nonce")
        .toBigIntegerExact(),
      rs.getString("status")
    );
  }
}