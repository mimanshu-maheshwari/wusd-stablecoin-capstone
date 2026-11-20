package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.domain.Deposit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
            status
        )
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (deposit_id) DO NOTHING
        """;

    int rows = jdbc.update(
        sql,
        deposit.depositId(),
        deposit.userId(),
        deposit.walletAddress(),
        new java.math.BigDecimal(
            deposit.amountUnits()),
        deposit.currency(),
        deposit.status());

    return rows == 1;
  }

  @Override
  public Optional<Deposit> findById(
      String depositId) {

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

    return jdbc.query(
        sql,
        (rs, rowNum) -> new Deposit(
            rs.getString("deposit_id"),
            rs.getString("user_id"),
            rs.getString("wallet_address"),
            rs.getBigDecimal("amount_units")
                .toBigIntegerExact(),
            rs.getString("currency"),
            rs.getString("status")),
        depositId).stream().findFirst();
  }
}
