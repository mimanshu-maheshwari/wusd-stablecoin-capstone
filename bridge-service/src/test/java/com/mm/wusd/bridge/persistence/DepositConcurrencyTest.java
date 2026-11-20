
package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.exception.DepositConflictException;
import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class DepositConcurrencyTest
  extends PostgresIntegrationTest {

  @Autowired
  private DepositService service;

  private DepositRequest request(
    String id,
    String amount) {

    return new DepositRequest(
      id,
      "USER-42",
      "0x1111111111111111111111111111111111111111",
      new BigDecimal(amount),
      "USD"
    );
  }

  @Test
  void exactlyOneWorkerWinsStatusTransition()
    throws Exception {

    String id = "DEP-" + UUID.randomUUID();

    service.create(request(id, "100.00"));

    int workerCount = 12;

    try (ExecutorService executor =
           Executors.newFixedThreadPool(workerCount)) {

      CountDownLatch ready =
        new CountDownLatch(workerCount);

      CountDownLatch start =
        new CountDownLatch(1);

      List<Future<Boolean>> results =
        new ArrayList<>();

      for (int i = 0; i < workerCount; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          start.await();

          return service.advanceStatus(
            id,
            "RECEIVED",
            "VALIDATED"
          );
        }));
      }

      assertTrue(
        ready.await(10, TimeUnit.SECONDS),
        "Workers did not reach the starting gate"
      );

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
      service.findById(id).status()
    );

    Long version = jdbc.queryForObject(
      """
        SELECT status_version
        FROM mint_operations
        WHERE deposit_id = ?
        """,
      Long.class,
      id
    );

    assertEquals(1L, version);
  }

  @Test
  void conflictingPayloadsNeverOverwriteWinner()
    throws Exception {

    String id = "DEP-" + UUID.randomUUID();
    int workerCount = 12;

    try (ExecutorService executor =
           Executors.newFixedThreadPool(workerCount)) {

      CountDownLatch ready =
        new CountDownLatch(workerCount);

      CountDownLatch start =
        new CountDownLatch(1);

      List<Future<String>> results =
        new ArrayList<>();

      for (int i = 0; i < workerCount; i++) {
        final String amount =
          i % 2 == 0 ? "100.00" : "200.00";

        results.add(executor.submit(() -> {
          ready.countDown();
          start.await();

          try {
            var result = service.create(
              request(id, amount)
            );

            return result.created()
              ? "CREATED"
              : "REPLAY";

          } catch (DepositConflictException ex) {
            return "CONFLICT";
          }
        }));
      }

      assertTrue(
        ready.await(10, TimeUnit.SECONDS)
      );

      start.countDown();

      int created = 0;
      int replayed = 0;
      int conflicts = 0;

      for (Future<String> result : results) {
        switch (result.get(30, TimeUnit.SECONDS)) {
          case "CREATED" -> created++;
          case "REPLAY" -> replayed++;
          case "CONFLICT" -> conflicts++;
          default -> fail("Unexpected result");
        }
      }

      assertEquals(1, created);
      assertEquals(5, replayed);
      assertEquals(6, conflicts);
    }

    Integer rows = jdbc.queryForObject(
      """
        SELECT COUNT(*)
        FROM mint_operations
        WHERE deposit_id = ?
        """,
      Integer.class,
      id
    );

    assertEquals(1, rows);
  }
}

