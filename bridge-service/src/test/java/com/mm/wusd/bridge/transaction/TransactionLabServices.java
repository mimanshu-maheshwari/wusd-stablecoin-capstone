package com.mm.wusd.bridge.transaction;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@TestConfiguration(proxyBeanMethods = false)
public class TransactionLabServices {

  @Bean
  SelfInvocationLab selfInvocationLab(JdbcTemplate jdbc) {
    return new SelfInvocationLab(jdbc);
  }

  @Bean
  TransactionalWriter transactionalWriter(JdbcTemplate jdbc) {
    return new TransactionalWriter(jdbc);
  }

  @Bean
  RollbackLab rollbackLab(JdbcTemplate jdbc) {
    return new RollbackLab(jdbc);
  }

  @Bean
  InnerPropagationLab innerPropagationLab(JdbcTemplate jdbc) {
    return new InnerPropagationLab(jdbc);
  }

  @Bean
  OuterPropagationLab outerPropagationLab(JdbcTemplate jdbc, InnerPropagationLab inner) {
    return new OuterPropagationLab(jdbc, inner);
  }

  public static class SelfInvocationLab {

    private final JdbcTemplate jdbc;

    public SelfInvocationLab(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    public void outer() {
      inner();
    }

    @Transactional
    public void inner() {
      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", "self-invocation");

      throw new IllegalStateException("Failure after self-invoked insert");
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

      throw new IllegalStateException("Failure after transactional insert");
    }
  }

  public static class RollbackLab {

    private final JdbcTemplate jdbc;

    public RollbackLab(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Transactional
    public void checkedDefault(String label) throws IOException {

      insert(label);
      throw new IOException("Checked failure");
    }

    @Transactional(rollbackFor = IOException.class)
    public void checkedWithRollback(String label) throws IOException {

      insert(label);
      throw new IOException("Checked failure");
    }

    @Transactional
    public void uncheckedFailure(String label) {
      insert(label);
      throw new IllegalStateException("Unchecked failure");
    }

    private void insert(String label) {
      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);
    }
  }

  public static class InnerPropagationLab {

    private final JdbcTemplate jdbc;

    public InnerPropagationLab(JdbcTemplate jdbc) {
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

  public static class OuterPropagationLab {

    private final JdbcTemplate jdbc;
    private final InnerPropagationLab inner;

    public OuterPropagationLab(JdbcTemplate jdbc, InnerPropagationLab inner) {

      this.jdbc = jdbc;
      this.inner = inner;
    }

    @Transactional
    public void outerFailsAfterRequiresNew() {

      insert("outer-rollback");

      inner.insertRequiresNew("inner-committed");

      throw new IllegalStateException("Outer transaction fails");
    }

    @Transactional
    public void catchInnerRequiredFailure() {

      insert("outer-required");

      try {
        inner.requiredFailure("inner-required");
      } catch (IllegalStateException ignored) {
        // Deliberately suppressing the application error
        // does not clear a rollback-only transaction.
      }
    }

    private void insert(String label) {
      jdbc.update("INSERT INTO tx_lesson_audit(label) VALUES (?)", label);
    }
  }
}
