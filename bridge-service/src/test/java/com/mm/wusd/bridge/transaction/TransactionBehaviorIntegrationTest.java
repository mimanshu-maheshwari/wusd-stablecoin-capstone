package com.mm.wusd.bridge.transaction;

import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.UnexpectedRollbackException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@Import(TransactionLabServices.class)
class TransactionBehaviorIntegrationTest extends PostgresIntegrationTest {

  @Autowired
  TransactionLabServices.SelfInvocationLab selfLab;

  @Autowired
  TransactionLabServices.TransactionalWriter writer;

  @Autowired
  TransactionLabServices.RollbackLab rollbackLab;

  @Autowired
  TransactionLabServices.OuterPropagationLab outerLab;

  @BeforeEach
  void prepareAuditTable() {

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

  @Test
  void selfInvocationBypassesTransactionalProxy() {

    assertThrows(IllegalStateException.class, selfLab::outer);

    assertEquals(1, count("self-invocation"));
  }

  @Test
  void externalTransactionalInvocationRollsBack() {

    assertThrows(IllegalStateException.class, () -> writer.insertThenFail("external-transaction"));

    assertEquals(0, count("external-transaction"));
  }

  @Test
  void checkedExceptionCommitsByDefault() throws IOException {

    assertThrows(IOException.class, () -> rollbackLab.checkedDefault("checked-default"));

    assertEquals(1, count("checked-default"));
  }

  @Test
  void rollbackForMakesCheckedExceptionRollback() throws IOException {

    assertThrows(IOException.class, () -> rollbackLab.checkedWithRollback("checked-rollback"));

    assertEquals(0, count("checked-rollback"));
  }

  @Test
  void runtimeExceptionRollsBack() {

    assertThrows(IllegalStateException.class, () -> rollbackLab.uncheckedFailure("unchecked" +
      "-rollback"));

    assertEquals(0, count("unchecked-rollback"));
  }

  @Test
  void requiresNewCommitsIndependently() {

    assertThrows(IllegalStateException.class, outerLab::outerFailsAfterRequiresNew);

    assertEquals(0, count("outer-rollback"));

    assertEquals(1, count("inner-committed"));
  }

  @Test
  void caughtInnerFailureStillCausesRollback() {

    assertThrows(UnexpectedRollbackException.class, outerLab::catchInnerRequiredFailure);

    assertEquals(0, count("outer-required"));

    assertEquals(0, count("inner-required"));
  }
}
