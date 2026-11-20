package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.domain.DepositFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class JdbcDepositRepository implements DepositRepository {

  private final JdbcTemplate jdbc;

  public JdbcDepositRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insertIfAbsent(Deposit deposit) {

    String sql = """
      INSERT INTO mint_operations (
          deposit_id,
          user_id,
          wallet_address,
          amount_units,
          currency,
          status,
          payload_hash
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (deposit_id) DO NOTHING
      """;

    int rows = jdbc.update(sql, deposit.depositId(), deposit.userId(), deposit.walletAddress(), new BigDecimal(deposit.amountUnits()), deposit.currency(), deposit.status(), DepositFingerprint.sha256(deposit));

    return rows == 1;
  }

  @Override
  public Optional<Deposit> findById(String depositId) {

    String sql = """
      SELECT
          deposit_id,
          user_id,
          wallet_address,
          amount_units,
          currency,
          status
      FROM mint_operations
      WHERE deposit_id = ?
      """;

    return jdbc.query(sql, (rs, rowNum) -> new Deposit(rs.getString("deposit_id"), rs.getString("user_id"), rs.getString("wallet_address"), rs.getBigDecimal("amount_units").toBigIntegerExact(), rs.getString("currency"), rs.getString("status")), depositId).stream().findFirst();
  }

  @Override
  public Optional<String> findPayloadHashById(String depositId) {

    String sql = """
      SELECT payload_hash
      FROM mint_operations
      WHERE deposit_id = ?
      """;

    return jdbc.query(sql, (rs, rowNum) -> rs.getString("payload_hash"), depositId).stream().findFirst();
  }

  @Override
  public boolean advanceStatus(String depositId, String expectedStatus, String nextStatus) {

    String sql = """
      UPDATE mint_operations
      SET status = ?,
          status_version = status_version + 1,
          updated_at = now()
      WHERE deposit_id = ?
        AND status = ?
      """;

    int rows = jdbc.update(sql, nextStatus, depositId, expectedStatus);

    return rows == 1;
  }
}
