package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DepositDatabaseContractTest
  extends PostgresIntegrationTest {

  @Autowired
  private DepositService service;

  @Autowired
  private DepositRepository repository;

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
  void depositSurvivesSeparateRepositoryRead() {

    String id = "DEP-" + UUID.randomUUID();

    service.create(request(id, "250.50"));

    var stored = repository.findById(id)
      .orElseThrow();

    assertEquals(
      "250500000",
      stored.amountUnits().toString()
    );
  }

  @Test
  void identicalRetryDoesNotCreateAnotherRow() {

    String id = "DEP-" + UUID.randomUUID();

    var first = service.create(
      request(id, "250.50")
    );

    var second = service.create(
      request(id, "250.50")
    );

    assertTrue(first.created());
    assertFalse(second.created());

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

  @Test
  void statusTransitionIncrementsVersion() {

    String id = "DEP-" + UUID.randomUUID();

    service.create(request(id, "100.00"));

    assertTrue(service.advanceStatus(
      id, "RECEIVED", "VALIDATED"
    ));

    assertFalse(service.advanceStatus(
      id, "RECEIVED", "VALIDATED"
    ));

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
  void flywayAppliedAllThreeMigrations() {

    Integer rows = jdbc.queryForObject(
      """
        SELECT COUNT(*)
        FROM flyway_schema_history
        WHERE version IN ('1', '2', '3')
          AND success = true
        """,
      Integer.class
    );

    assertEquals(3, rows);
  }
}

