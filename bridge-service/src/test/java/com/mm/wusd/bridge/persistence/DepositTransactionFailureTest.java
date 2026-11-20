package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.exception.DepositNotFoundException;
import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DepositTransactionFailureTest extends PostgresIntegrationTest {

  @Autowired
  private DepositService service;

  @Autowired
  private TransactionTemplate transactions;

  private DepositRequest request(String id) {
    return new DepositRequest(
      id,
      "USER-42",
      "0x1111111111111111111111111111111111111111",
      new BigDecimal("250.50"),
      "USD"
    );
  }

  @Test
  void failedTransactionRollsBackInsert() {

    String id = "DEP-" + UUID.randomUUID();

    assertThrows(
      IllegalStateException.class,
      () -> transactions.executeWithoutResult(status -> {

        service.create(request(id));

        throw new IllegalStateException(
          "Simulated failure after insert"
        );
      })
    );

    assertThrows(
      DepositNotFoundException.class,
      () -> service.findById(id)
    );
  }

  @Test
  void failedTransactionRollsBackStatusChange() {

    String id = "DEP-" + UUID.randomUUID();

    service.create(request(id));

    assertThrows(
      IllegalStateException.class,
      () -> transactions.executeWithoutResult(status -> {

        assertTrue(service.advanceStatus(
          id,
          "RECEIVED",
          "VALIDATED"
        ));

        throw new IllegalStateException(
          "Simulated failure after update"
        );
      })
    );

    assertEquals(
      "RECEIVED",
      service.findById(id).status()
    );
  }

  @Test
  void databaseRejectsImmutableAmountChanges() {

    String id = "DEP-" + UUID.randomUUID();

    service.create(request(id));

    assertThrows(
      DataAccessException.class,
      () -> jdbc.update(
        """
          UPDATE mint_operations
          SET amount_units = 999999999
          WHERE deposit_id = ?
          """,
        id
      )
    );

    assertEquals(
      "250500000",
      service.findById(id)
        .amountUnits()
        .toString()
    );
  }
}