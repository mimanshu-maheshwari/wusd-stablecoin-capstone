package com.mm.wusd.bridge.besu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionJournal {

  private final JdbcTemplate jdbc;

  public TransactionJournal(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void saveSigned(PreparedTransaction tx) {

    jdbc.update("""
        INSERT INTO besu_transaction_probe (
            chain_id,
            sender_address,
            account_nonce,
            recipient_address,
            transaction_hash,
            signed_raw_tx,
            status
        )
        VALUES (?, ?, ?, ?, ?, ?, 'SIGNED')
        """,
      tx.chainId(),
      tx.sender().toLowerCase(),
      tx.nonce(),
      tx.recipient().toLowerCase(),
      tx.transactionHash().toLowerCase(),
      tx.signedHex()
    );
  }

  public boolean markAccepted(String hash) {

    return jdbc.update("""
        UPDATE besu_transaction_probe
        SET status = 'SUBMISSION_ACCEPTED',
            last_error = NULL,
            updated_at = now()
        WHERE transaction_hash = ?
          AND status IN ('SIGNED', 'UNCERTAIN')
        """,
      hash.toLowerCase()
    ) == 1;
  }

  public boolean markUncertain(
    String hash,
    String reason) {

    return jdbc.update("""
        UPDATE besu_transaction_probe
        SET status = 'UNCERTAIN',
            last_error = ?,
            updated_at = now()
        WHERE transaction_hash = ?
          AND status IN (
              'SIGNED',
              'SUBMISSION_ACCEPTED',
              'UNCERTAIN'
          )
        """,
      reason,
      hash.toLowerCase()
    ) == 1;
  }

  public boolean recordReceipt(
    String hash,
    String blockHash,
    long blockNumber,
    boolean succeeded) {

    return jdbc.update("""
        UPDATE besu_transaction_probe
        SET status = ?,
            receipt_block_hash = ?,
            receipt_block_number = ?,
            last_error = NULL,
            updated_at = now()
        WHERE transaction_hash = ?
          AND status NOT IN (
              'INCLUDED_SUCCESS',
              'INCLUDED_REVERTED'
          )
        """,
      succeeded
        ? "INCLUDED_SUCCESS"
        : "INCLUDED_REVERTED",
      blockHash,
      blockNumber,
      hash.toLowerCase()
    ) == 1;
  }

  public String status(String hash) {

    return jdbc.queryForObject("""
        SELECT status
        FROM besu_transaction_probe
        WHERE transaction_hash = ?
        """,
      String.class,
      hash.toLowerCase()
    );
  }
}
