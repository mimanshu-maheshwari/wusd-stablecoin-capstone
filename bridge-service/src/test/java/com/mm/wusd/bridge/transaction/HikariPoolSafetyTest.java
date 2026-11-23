package com.mm.wusd.bridge.transaction;

import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class HikariPoolSafetyTest extends PostgresIntegrationTest {

  private HikariDataSource createOneConnectionPool() {

    HikariConfig config = new HikariConfig();

    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());

    config.setPoolName("lesson8-one-connection-pool");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1000);

    return new HikariDataSource(config);
  }

  @Test
  void externalCallInsideTransactionExhaustsPool() throws Exception {

    try (HikariDataSource dataSource = createOneConnectionPool(); ExecutorService executor =
      Executors.newFixedThreadPool(2)) {

      JdbcTemplate labJdbc = new JdbcTemplate(dataSource);

      TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

      CountDownLatch connectionHeld = new CountDownLatch(1);

      CountDownLatch releaseExternalCall = new CountDownLatch(1);

      Future<?> slowOperation = executor.submit(() -> {

        transactions.executeWithoutResult(status -> {

          // Ensure the database is accessed
          // while the transaction is active.
          labJdbc.queryForObject("SELECT 1", Integer.class);

          connectionHeld.countDown();

          try {
            // Simulates an RPC call blocked
            // while the transaction remains open.
            if (!releaseExternalCall.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Timed out waiting for release");
            }
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
          }
        });
      });

      try {
        assertTrue(connectionHeld.await(5, TimeUnit.SECONDS));

        Future<Integer> competingQuery = executor.submit(() -> labJdbc.queryForObject("SELECT 1",
          Integer.class));

        ExecutionException failure = assertThrows(ExecutionException.class,
          () -> competingQuery.get(5, TimeUnit.SECONDS));

        assertInstanceOf(org.springframework.dao.DataAccessException.class, failure.getCause());

        System.out.println("Observed expected Hikari connection exhaustion: " + failure.getCause().getMessage());

      } finally {
        releaseExternalCall.countDown();
        slowOperation.get(15, TimeUnit.SECONDS);
      }

      // The connection is returned after
      // the transaction finishes.
      assertEquals(1, labJdbc.queryForObject("SELECT 1", Integer.class));
    }
  }

  @Test
  void externalCallOutsideTransactionLeavesPoolAvailable() throws Exception {

    try (HikariDataSource dataSource = createOneConnectionPool(); ExecutorService executor =
      Executors.newFixedThreadPool(2)) {

      JdbcTemplate labJdbc = new JdbcTemplate(dataSource);

      TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

      CountDownLatch externalCallStarted = new CountDownLatch(1);

      CountDownLatch releaseExternalCall = new CountDownLatch(1);

      Future<?> slowOperation = executor.submit(() -> {

        // Short database transaction.
        transactions.executeWithoutResult(status -> {
          labJdbc.queryForObject("SELECT 1", Integer.class);
        });

        // Transaction has ended here.
        externalCallStarted.countDown();

        try {
          // Simulates slow Besu RPC,
          // but no JDBC transaction is open.
          if (!releaseExternalCall.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for release");
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
      });

      try {
        assertTrue(externalCallStarted.await(5, TimeUnit.SECONDS));

        Future<Integer> competingQuery = executor.submit(() -> labJdbc.queryForObject("SELECT 1",
          Integer.class));

        assertEquals(1, competingQuery.get(5, TimeUnit.SECONDS));

      } finally {
        releaseExternalCall.countDown();
        slowOperation.get(15, TimeUnit.SECONDS);
      }
    }
  }
}
