
package com.mm.wusd.bridge.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Repository
public class MintOutboxRepository {

  private final JdbcTemplate jdbc;

  public MintOutboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean enqueue(String depositId) {

    int inserted = jdbc.update("""
        INSERT INTO mint_outbox (
            deposit_id,
            event_type,
            status
        )
        VALUES (?, 'MINT_REQUESTED', 'READY')
        ON CONFLICT (deposit_id, event_type)
        DO NOTHING
        """,
      depositId
    );

    return inserted == 1;
  }

  @Transactional
  public List<MintWorkClaim> claimBatch(
    String workerId,
    int batchSize,
    Duration leaseDuration) {

    validateWorker(workerId);

    if (batchSize < 1 || batchSize > 100) {
      throw new IllegalArgumentException(
        "batchSize must be between 1 and 100"
      );
    }

    long seconds = leaseSeconds(leaseDuration);

    return jdbc.query("""
        WITH available AS (
            SELECT id
            FROM mint_outbox
            WHERE status = 'READY'
               OR (
                   status = 'CLAIMED'
                   AND lease_expires_at <= now()
               )
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        )
        UPDATE mint_outbox AS work
        SET
            status = 'CLAIMED',
            claimed_by = ?,
            lease_token = work.lease_token + 1,
            lease_expires_at =
                now() + (? * INTERVAL '1 second'),
            attempt_count = work.attempt_count + 1,
            updated_at = now()
        FROM available
        WHERE work.id = available.id
        RETURNING
            work.id,
            work.deposit_id,
            work.lease_token,
            work.claimed_by,
            work.lease_expires_at
        """,
      (rs, rowNum) -> new MintWorkClaim(
        rs.getLong("id"),
        rs.getString("deposit_id"),
        rs.getLong("lease_token"),
        rs.getString("claimed_by"),
        rs.getTimestamp("lease_expires_at").toInstant()
      ),
      batchSize,
      workerId,
      seconds
    );
  }

  public boolean renewLease(
    MintWorkClaim claim,
    Duration leaseDuration) {

    int updated = jdbc.update("""
        UPDATE mint_outbox
        SET
            lease_expires_at =
                now() + (? * INTERVAL '1 second'),
            updated_at = now()
        WHERE id = ?
          AND status = 'CLAIMED'
          AND claimed_by = ?
          AND lease_token = ?
          AND lease_expires_at > now()
        """,
      leaseSeconds(leaseDuration),
      claim.id(),
      claim.claimedBy(),
      claim.leaseToken()
    );

    return updated == 1;
  }

  public boolean markDemoDone(MintWorkClaim claim) {

    int updated = jdbc.update("""
        UPDATE mint_outbox
        SET
            status = 'DEMO_DONE',
            claimed_by = NULL,
            lease_expires_at = NULL,
            completed_at = now(),
            updated_at = now()
        WHERE id = ?
          AND status = 'CLAIMED'
          AND claimed_by = ?
          AND lease_token = ?
          AND lease_expires_at > now()
        """,
      claim.id(),
      claim.claimedBy(),
      claim.leaseToken()
    );

    return updated == 1;
  }

  public long countByDepositId(String depositId) {

    Long count = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM mint_outbox
        WHERE deposit_id = ?
        """,
      Long.class,
      depositId
    );

    return count == null ? 0 : count;
  }

  private static long leaseSeconds(Duration duration) {

    if (duration == null
      || duration.isNegative()
      || duration.isZero()
      || duration.getNano() != 0) {
      throw new IllegalArgumentException(
        "Lease duration must be positive whole seconds"
      );
    }

    return duration.getSeconds();
  }

  private static void validateWorker(String workerId) {

    if (workerId == null
      || workerId.isBlank()
      || workerId.length() > 128) {
      throw new IllegalArgumentException(
        "Invalid worker ID"
      );
    }
  }
}
