package com.mm.wusd.bridge.outbox;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.inbox.FiatDepositRecordProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
  "spring.kafka.listener.auto-startup=false",
  "spring.kafka.listener.ack-mode=manual_immediate",
  "spring.kafka.admin.auto-create=false",
  "wusd.outbox.worker.enabled=false"
})
class MintOutboxIntegrationTest {

  static final PostgreSQLContainer POSTGRES =
    new PostgreSQLContainer("postgres:17")
      .withDatabaseName("wusd_outbox_it")
      .withUsername("test")
      .withPassword("test");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MintOutboxRepository outbox;

  @Autowired
  FiatDepositRecordProcessor processor;

  @BeforeEach
  void cleanTables() {
    jdbc.update("DELETE FROM mint_outbox");
    jdbc.update("DELETE FROM kafka_inbox");
    jdbc.update("DELETE FROM mint_operations");
  }

  private static String newId() {
    return "DEP-" + UUID.randomUUID();
  }

  private static DepositRequest deposit(String id, String amount) {

    return new DepositRequest(id, "USER-42", "0x1111111111111111111111111111111111111111",
      new BigDecimal(amount), "USD");
  }

  private static ConsumerRecord<String, String> record(String id, long offset) {

    return new ConsumerRecord<>(
      "fiat.deposited.v1",
      3,
      offset,
      id,
      "{\"depositId\":\"" + id + "\"}"
    );
  }

  private long count(String table) {

    // Only used with fixed, internal table names.
    Long result = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);

    return result == null ? 0 : result;
  }

  @Test
  void atomicInboxDepositAndOutboxCreation() {
    String id = newId();
    processor.process(record(id, 100L), deposit(id, "250.50"));

    assertEquals(1, count("kafka_inbox"));
    assertEquals(1, count("mint_operations"));
    assertEquals(1, count("mint_outbox"));

    assertEquals(1, outbox.countByDepositId(id));
  }

  @Test
  void identicalBusinessRetryCreatesOneOutboxRow() {

    String id = newId();

    processor.process(record(id, 101L), deposit(id, "100.00"));

    processor.process(record(id, 102L), deposit(id, "100.00"));

    assertEquals(2, count("kafka_inbox"));
    assertEquals(1, count("mint_operations"));
    assertEquals(1, count("mint_outbox"));
  }

  @Test
  void replayedPhysicalRecordCreatesNoNewWork() {

    String id = newId();

    var kafkaRecord = record(id, 103L);
    var request = deposit(id, "100.00");

    processor.process(kafkaRecord, request);
    processor.process(kafkaRecord, request);

    assertEquals(1, count("kafka_inbox"));
    assertEquals(1, count("mint_operations"));
    assertEquals(1, count("mint_outbox"));
  }

  @Test
  void conflictRollsBackNewInboxAndOutboxWrites() {

    String id = newId();

    processor.process(record(id, 104L), deposit(id, "100.00"));

    assertThrows(RuntimeException.class, () -> processor.process(record(id, 105L), deposit(id,
      "500.00")));

    assertEquals(1, count("kafka_inbox"));
    assertEquals(1, count("mint_operations"));
    assertEquals(1, count("mint_outbox"));
  }

  @Test
  void expiredLeaseCanBeReclaimedAndOldWorkerIsFenced() {

    String id = newId();

    assertTrue(outbox.enqueue(id));

    MintWorkClaim first = outbox.claimBatch("worker-A", 1, Duration.ofSeconds(30)).getFirst();

    assertEquals(1, first.leaseToken());

    // Simulate expiration using PostgreSQL time.
    jdbc.update("""
      UPDATE mint_outbox
      SET lease_expires_at = now() - INTERVAL '1 second'
      WHERE id = ?
      """, first.id());

    MintWorkClaim second = outbox.claimBatch("worker-B", 1, Duration.ofSeconds(30)).getFirst();

    assertEquals(first.id(), second.id());
    assertEquals(2, second.leaseToken());
    assertFalse(outbox.markDemoDone(first), "Stale worker must not complete work");
    assertFalse(outbox.renewLease(first, Duration.ofSeconds(30)));
    assertTrue(outbox.markDemoDone(second));
    assertEquals("DEMO_DONE", jdbc.queryForObject("SELECT status FROM mint_outbox WHERE id = ?",
      String.class, second.id()));
  }

  @Test
  void threeWorkersClaimDistinctRows() throws Exception {
    for (int i = 0; i < 24; i++) {
      assertTrue(outbox.enqueue(newId()));
    }

    CountDownLatch ready = new CountDownLatch(3);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {

      List<Future<List<MintWorkClaim>>> futures = IntStream.range(0, 3).mapToObj(
        i -> pool.submit(() -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Start signal timed out");
          }
          return outbox.claimBatch("worker-" + i, 8, Duration.ofSeconds(30));
        })).toList();

      assertTrue(ready.await(10, TimeUnit.SECONDS));

      start.countDown();
      Set<Long> claimedIds = new HashSet<>();

      for (Future<List<MintWorkClaim>> future : futures) {
        for (MintWorkClaim claim : future.get(15, TimeUnit.SECONDS)) {
          assertTrue(claimedIds.add(claim.id()), "Duplicate work claim: " + claim.id());
        }
      }

      assertEquals(24, claimedIds.size());
    }
  }

  @Test
  void outboxFailureRollsBackInboxAndDeposit() {

    String id = newId();

    // Simulate a database-level outbox failure.
    jdbc.execute("""
      ALTER TABLE mint_outbox
      ADD CONSTRAINT chk_test_reject_all_inserts
      CHECK (false)
      """);

    try {
      assertThrows(
        RuntimeException.class,
        () -> processor.process(
          record(id, 200L),
          deposit(id, "100.00")
        )
      );

      assertEquals(0, count("kafka_inbox"));
      assertEquals(0, count("mint_operations"));
      assertEquals(0, count("mint_outbox"));

    } finally {
      jdbc.execute("""
        ALTER TABLE mint_outbox
        DROP CONSTRAINT chk_test_reject_all_inserts
        """);
    }
  }

}