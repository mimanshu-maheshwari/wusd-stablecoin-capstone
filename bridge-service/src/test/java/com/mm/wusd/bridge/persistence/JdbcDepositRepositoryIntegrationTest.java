package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.domain.Deposit;

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
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class JdbcDepositRepositoryIntegrationTest {

  @Container
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
      .withDatabaseName("wusd_test")
      .withUsername("test")
      .withPassword("test");

  @DynamicPropertySource
  static void postgresProperties(
      DynamicPropertyRegistry registry) {

    registry.add(
        "spring.datasource.url",
        postgres::getJdbcUrl);

    registry.add(
        "spring.datasource.username",
        postgres::getUsername);

    registry.add(
        "spring.datasource.password",
        postgres::getPassword);
  }

  @Autowired
  JdbcDepositRepository repository;

  @Autowired
  DepositService service;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TransactionTemplate transactions;

  @BeforeEach
  void cleanDatabase() {
    jdbc.update("DELETE FROM mint_operations");
  }

  private Deposit deposit(String id) {
    return new Deposit(
        id,
        "USER-42",
        "0x1111111111111111111111111111111111111111",
        new BigInteger("250500000"),
        "USD",
        "RECEIVED");
  }

  @Test
  void flywayCreatedTheExpectedTable() {

    Integer count = jdbc.queryForObject(
        """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = 'mint_operations'
            """,
        Integer.class);

    assertEquals(1, count);
  }

  @Test
  void persistsAndReadsDeposit() {

    assertTrue(
        repository.insertIfAbsent(deposit("DEP-1")));

    Deposit stored = repository
        .findById("DEP-1")
        .orElseThrow();

    assertEquals("USER-42", stored.userId());

    assertEquals(
        new BigInteger("250500000"),
        stored.amountUnits());
  }

  @Test
  void preventsDuplicateInsertion() {

    assertTrue(
        repository.insertIfAbsent(deposit("DEP-2")));

    assertFalse(
        repository.insertIfAbsent(deposit("DEP-2")));

    Integer count = jdbc.queryForObject(
        """
            SELECT COUNT(*)
            FROM mint_operations
            WHERE deposit_id = ?
            """,
        Integer.class,
        "DEP-2");

    assertEquals(1, count);
  }

  @Test
  void transactionRollbackRemovesUncommittedInsert() {

    assertThrows(
        IllegalStateException.class,
        () -> transactions.executeWithoutResult(
            status -> {
              repository.insertIfAbsent(
                  deposit("DEP-ROLLBACK"));

              throw new IllegalStateException(
                  "Simulated failure");
            }));

    assertTrue(
        repository.findById("DEP-ROLLBACK")
            .isEmpty());
  }

  @Test
  void serviceWorksWithRealPostgres() {

    DepositRequest request = new DepositRequest(
        "DEP-SERVICE",
        "USER-42",
        "0x1111111111111111111111111111111111111111",
        new BigDecimal("250.50"),
        "USD");

    assertTrue(service.create(request).created());

    assertFalse(service.create(request).created());

    assertEquals(
        "250500000",
        service.findById("DEP-SERVICE")
            .amountUnits()
            .toString());
  }

  @Test
  void concurrentInsertsCreateOnlyOneRecord()
      throws Exception {

    int workers = 20;

    try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {

      CountDownLatch ready = new CountDownLatch(workers);

      CountDownLatch start = new CountDownLatch(1);

      List<Future<Boolean>> results = new java.util.ArrayList<>();

      for (int i = 0; i < workers; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          start.await();

          return repository.insertIfAbsent(
              deposit("DEP-CONCURRENT"));
        }));
      }

      assertTrue(
          ready.await(10, TimeUnit.SECONDS));

      start.countDown();

      int successfulInserts = 0;

      for (Future<Boolean> result : results) {
        if (result.get(30, TimeUnit.SECONDS)) {
          successfulInserts++;
        }
      }

      assertEquals(1, successfulInserts);
    }

    Integer rows = jdbc.queryForObject(
        """
            SELECT COUNT(*)
            FROM mint_operations
            WHERE deposit_id = ?
            """,
        Integer.class,
        "DEP-CONCURRENT");

    assertEquals(1, rows);
  }
}
