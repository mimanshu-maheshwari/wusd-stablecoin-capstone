package com.mm.wusd.bridge.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
  "spring.kafka.listener.auto-startup=false",
  "spring.kafka.listener.ack-mode=manual_immediate",
  "spring.kafka.admin.auto-create=false"
})
@Import(TransactionLabIntegrationTest.LabConfiguration.class)
class TransactionLabIntegrationTest {

  static final PostgreSQLContainer POSTGRES =
    new PostgreSQLContainer("postgres:17")
      .withDatabaseName("wusd_tx_lab")
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
  SelfInvocationService selfInvocationService;

  @Autowired
  TransactionalWriter transactionalWriter;

  @Autowired
  RollbackService rollbackService;

  @Autowired
  OuterService outerService;

  @BeforeEach
  void createAndCleanAuditTable() {

    jdbc.execute("""
      CREATE TABLE IF NOT EXISTS tx_lesson_audit (
          id BIGSERIAL PRIMARY KEY,
          label VARCHAR(128) NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT now()
      )
      """);

    jdbc.update("DELETE FROM tx_lesson_audit");
  }

  private int count(String label) {

    Integer result = jdbc.queryForObject("""
      SELECT COUNT(*)
      FROM tx_lesson_audit
      WHERE label = ?
      """, Integer.class, label);

    return result == null ? 0 : result;
  }

  // EXPERIMENT 1:
  // Internal method invocation bypasses the proxy.
  @Test
  void selfInvocationDoesNotStartTransaction() {

    assertThrows(IllegalStateException.class, selfInvocationService::outer);

    assertEquals(1, count("self-invoked"));
  }

  // EXPERIMENT 2:
  // External proxy invocation starts a transaction.
  @Test
  void externalInvocationRollsBack() {

    assertThrows(
      IllegalStateException.class,
      () -> transactionalWriter.insertThenFail("proxy-invoked")
    );

    assertEquals(0, count("proxy-invoked"));
  }

  // EXPERIMENT 3:
  // Checked exception does not roll back by default.
  @Test
  void checkedExceptionCommitsByDefault() {

    assertThrows(IOException.class, () -> rollbackService.checkedDefault("checked-default"));

    assertEquals(1, count("checked-default"));
  }

  // EXPERIMENT 4:
  // Explicit rollbackFor changes the rule.
  @Test
  void checkedExceptionRollsBackWhenConfigured() {

    assertThrows(IOException.class, () -> rollbackService.checkedWithRollback("checked-rollback"));
    assertEquals(0, count("checked-rollback"));
  }

  // EXPERIMENT 5:
  // Runtime exceptions roll back by default.
  @Test
  void uncheckedExceptionRollsBack() {

    assertThrows(
      IllegalStateException.class,
      () -> rollbackService.uncheckedFailure("unchecked-failure")
    );

    assertEquals(0, count("unchecked-failure"));
  }

  // EXPERIMENT 6:
  // REQUIRES_NEW commits independently.
  @Test
  void requiresNewCommitsEvenWhenOuterFails() {

    assertThrows(IllegalStateException.class, outerService::outerFailsAfterRequiresNew);

    assertEquals(0, count("outer-requires-new"));

    assertEquals(1, count("inner-requires-new"));
  }

  // EXPERIMENT 7:
  // Catching an exception cannot clear rollback-only.
  @Test
  void caughtRequiredFailureCausesUnexpectedRollback() {

    assertThrows(UnexpectedRollbackException.class, outerService::catchRequiredFailure);

    assertEquals(0, count("outer-required"));

    assertEquals(0, count("inner-required"));
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class LabConfiguration {

    @Bean
    SelfInvocationService selfInvocationService(JdbcTemplate jdbc) {

      return new SelfInvocationService(jdbc);
    }

    @Bean
    TransactionalWriter transactionalWriter(JdbcTemplate jdbc) {

      return new TransactionalWriter(jdbc);
    }

    @Bean
    RollbackService rollbackService(JdbcTemplate jdbc) {

      return new RollbackService(jdbc);
    }

    @Bean
    InnerService innerService(JdbcTemplate jdbc) {

      return new InnerService(jdbc);
    }

    @Bean
    OuterService outerService(JdbcTemplate jdbc, InnerService innerService) {

      return new OuterService(jdbc, innerService);
    }
  }

  public static class SelfInvocationService {

    private final JdbcTemplate jdbc;

    public SelfInvocationService(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    public void outer() {
      inner();
    }

    @Transactional
    public void inner() {

      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", "self-invoked");

      throw new IllegalStateException("Failure after self-invocation");
    }
  }

  public static class TransactionalWriter {

    private final JdbcTemplate jdbc;

    public TransactionalWriter(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Transactional
    public void insertThenFail(String label) {

      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);

      throw new IllegalStateException("Failure after insert");
    }
  }

  public static class RollbackService {

    private final JdbcTemplate jdbc;

    public RollbackService(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Transactional
    public void checkedDefault(String label) throws IOException {

      insert(label);

      throw new IOException("Checked exception");
    }

    @Transactional(rollbackFor = IOException.class)
    public void checkedWithRollback(String label) throws IOException {

      insert(label);

      throw new IOException("Checked exception");
    }

    @Transactional
    public void uncheckedFailure(String label) {

      insert(label);

      throw new IllegalStateException("Unchecked exception");
    }

    private void insert(String label) {
      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);
    }
  }

  public static class InnerService {

    private final JdbcTemplate jdbc;

    public InnerService(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertRequiresNew(String label) {

      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void requiredFailure(String label) {

      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);

      throw new IllegalStateException("Inner REQUIRED failed");
    }
  }

  public static class OuterService {

    private final JdbcTemplate jdbc;
    private final InnerService inner;

    public OuterService(JdbcTemplate jdbc, InnerService inner) {

      this.jdbc = jdbc;
      this.inner = inner;
    }

    @Transactional
    public void outerFailsAfterRequiresNew() {

      insert("outer-requires-new");

      inner.insertRequiresNew("inner-requires-new");

      throw new IllegalStateException("Outer transaction failed");
    }

    @Transactional
    public void catchRequiredFailure() {

      insert("outer-required");

      try {
        inner.requiredFailure("inner-required");
      } catch (IllegalStateException ignored) {
        // The shared transaction is rollback-only.
      }
    }

    private void insert(String label) {

      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);
    }
  }
}

