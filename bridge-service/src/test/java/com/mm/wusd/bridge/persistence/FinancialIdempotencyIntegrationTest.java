package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.domain.DepositFingerprint;
import com.mm.wusd.bridge.exception.DepositConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
public class FinancialIdempotencyIntegrationTest {

  @Container
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17").withDatabaseName("wusd_financial_test").withUsername("test").withPassword("test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {

    registry.add("spring.datasource.url", postgres::getJdbcUrl);

    registry.add("spring.datasource.username", postgres::getUsername);

    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  DepositService service;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate transactions;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM mint_operations");
  }

  private DepositRequest request(String id, String amount) {

    return new DepositRequest(id, "USER-42", "0x1111111111111111111111111111111111111111", new BigDecimal(amount), "USD");
  }

  @Test
  void hashMatchesPostgresCanonicalAlgorithm() {

    service.create(request("DEP-HASH", "250.50"));

    var deposit = service.findById("DEP-HASH");

    String javaHash = DepositFingerprint.sha256(deposit);

    String sqlHash = jdbc.queryForObject(
      """
        SELECT encode(
            digest(
                concat_ws(
                    E'\\n',
                    'wusd-deposit-v1',
                    deposit_id,
                    user_id,
                    lower(wallet_address),
                    amount_units::text,
                    currency
                ),
                'sha256'
            ),
            'hex'
        )
        FROM mint_operations
        WHERE deposit_id = ?
        """,
      String.class,
      "DEP-HASH"
    );

    assertEquals(javaHash, sqlHash);
  }

  @Test
  void retryAfterStatusChangesSucceeds() {

    DepositRequest original =
      request("DEP-RETRY", "250.50");

    assertTrue(service.create(original).created());

    assertTrue(service.advanceStatus(
      "DEP-RETRY",
      "RECEIVED",
      "VALIDATED"
    ));

    assertTrue(service.advanceStatus(
      "DEP-RETRY",
      "VALIDATED",
      "MINTING"
    ));

    assertTrue(service.advanceStatus(
      "DEP-RETRY",
      "MINTING",
      "MINTED"
    ));

    var retry = service.create(original);

    assertFalse(retry.created());
    assertEquals("MINTED", retry.deposit().status());

    Long version = jdbc.queryForObject(
      """
        SELECT status_version
        FROM mint_operations
        WHERE deposit_id = ?
        """,
      Long.class,
      "DEP-RETRY"
    );

    assertEquals(3L, version);
  }

  @Test
  void concurrentCompetingTransitionsHaveOneWinner()
    throws Exception {

    service.create(request("DEP-RACE", "100.00"));

    int workers = 20;

    try (ExecutorService executor =
           Executors.newFixedThreadPool(workers)) {

      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();

      for (int i = 0; i < workers; i++) {
        results.add(executor.submit(() -> {
          start.await();

          return service.advanceStatus(
            "DEP-RACE",
            "RECEIVED",
            "VALIDATED"
          );
        }));
      }

      start.countDown();

      int winners = 0;

      for (Future<Boolean> result : results) {
        if (result.get(30, TimeUnit.SECONDS)) {
          winners++;
        }
      }

      assertEquals(1, winners);
    }

    assertEquals(
      "VALIDATED",
      service.findById("DEP-RACE").status()
    );

    Long version = jdbc.queryForObject(
      """
        SELECT status_version
        FROM mint_operations
        WHERE deposit_id = 'DEP-RACE'
        """,
      Long.class
    );

    assertEquals(1L, version);
  }

  @Test
  void competingDepositPayloadsCannotBothWin()
    throws Exception {

    int workers = 24;

    try (ExecutorService executor =
           Executors.newFixedThreadPool(12)) {

      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();

      for (int i = 0; i < workers; i++) {
        final String amount =
          i % 2 == 0 ? "250.50" : "500.00";

        results.add(executor.submit(() -> {
          start.await();

          try {
            var result = service.create(
              request("DEP-COMPETE", amount)
            );

            return result.created()
              ? "CREATED"
              : "REPLAY";

          } catch (DepositConflictException ex) {
            return "CONFLICT";
          }
        }));
      }

      start.countDown();

      int created = 0;
      int replayed = 0;
      int conflicted = 0;

      for (Future<String> future : results) {
        switch (future.get(30, TimeUnit.SECONDS)) {
          case "CREATED" -> created++;
          case "REPLAY" -> replayed++;
          case "CONFLICT" -> conflicted++;
          default -> fail("Unexpected result");
        }
      }

      assertEquals(1, created);
      assertEquals(11, replayed);
      assertEquals(12, conflicted);
    }

    Integer rows = jdbc.queryForObject(
      """
        SELECT COUNT(*)
        FROM mint_operations
        WHERE deposit_id = 'DEP-COMPETE'
        """,
      Integer.class
    );

    assertEquals(1, rows);
  }

  @Test
  void failedTransactionRollsBackStatusChange() {

    service.create(request("DEP-ROLLBACK", "100.00"));

    assertThrows(
      IllegalStateException.class,
      () -> transactions.executeWithoutResult(status -> {

        assertTrue(service.advanceStatus(
          "DEP-ROLLBACK",
          "RECEIVED",
          "VALIDATED"
        ));

        throw new IllegalStateException(
          "Simulated failure"
        );
      })
    );

    assertEquals(
      "RECEIVED",
      service.findById("DEP-ROLLBACK").status()
    );
  }

  @Test
  void databaseRejectsImmutableAmountChange() {

    service.create(request("DEP-IMMUTABLE", "100.00"));

    assertThrows(
      org.springframework.dao.DataAccessException.class,
      () -> jdbc.update(
        """
          UPDATE mint_operations
          SET amount_units = ?
          WHERE deposit_id = ?
          """,
        new BigDecimal("999999999"),
        "DEP-IMMUTABLE"
      )
    );
  }

}
