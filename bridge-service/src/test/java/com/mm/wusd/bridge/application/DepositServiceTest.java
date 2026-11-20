
package com.mm.wusd.bridge.application;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.exception.DepositConflictException;
import com.mm.wusd.bridge.exception.DepositNotFoundException;
import com.mm.wusd.bridge.persistence.InMemoryDepositRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class DepositServiceTest {

  private DepositService service;

  @BeforeEach
  void setUp() {
    service = new DepositService(
        new InMemoryDepositRepository());
  }

  private DepositRequest request(
      String id,
      String amount) {
    return new DepositRequest(
        id,
        "USER-42",
        "0x1111111111111111111111111111111111111111",
        new BigDecimal(amount),
        "USD");
  }

  @Test
  void createsNewDeposit() {
    var result = service.create(
        request("DEP-1", "250.50"));

    assertTrue(result.created());

    assertEquals(
        "250500000",
        result.deposit().amountUnits().toString());
  }

  @Test
  void identicalRetryDoesNotCreateAgain() {
    service.create(request("DEP-1", "250.50"));

    var retry = service.create(
        request("DEP-1", "250.50"));

    assertFalse(retry.created());
  }

  @Test
  void rejectsConflictingDeposit() {
    service.create(request("DEP-1", "250.50"));

    assertThrows(
        DepositConflictException.class,
        () -> service.create(
            request("DEP-1", "500.00")));
  }

  @Test
  void reportsMissingDeposit() {
    assertThrows(
        DepositNotFoundException.class,
        () -> service.findById("UNKNOWN"));
  }
}
