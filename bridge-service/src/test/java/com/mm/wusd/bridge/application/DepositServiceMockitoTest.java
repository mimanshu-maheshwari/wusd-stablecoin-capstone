
package com.mm.wusd.bridge.application;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.domain.DepositFingerprint;
import com.mm.wusd.bridge.exception.DepositConflictException;
import com.mm.wusd.bridge.persistence.DepositRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceMockitoTest {

  @Mock
  private DepositRepository repository;

  private DepositService service;

  private DepositRequest request;

  @BeforeEach
  void setUp() {
    service = new DepositService(repository);

    request = new DepositRequest(
      "DEP-1001",
      "USER-42",
      "0x1111111111111111111111111111111111111111",
      new BigDecimal("250.50"),
      "USD"
    );
  }

  @Test
  void firstDepositIsInsertedExactlyOnce() {

    when(repository.insertIfAbsent(any(Deposit.class)))
      .thenReturn(true);

    var result = service.create(request);

    assertTrue(result.created());

    verify(repository, times(1))
      .insertIfAbsent(any(Deposit.class));

    verify(repository, never())
      .findPayloadHashById(anyString());

    verifyNoMoreInteractions(repository);
  }

  @Test
  void identicalRetryReturnsExistingDeposit() {

    when(repository.insertIfAbsent(any(Deposit.class)))
      .thenReturn(false);

    Deposit existing = new Deposit(
      request.depositId(),
      request.userId(),
      request.walletAddress(),
      new java.math.BigInteger("250500000"),
      request.currency(),
      "MINTED"
    );

    when(repository.findPayloadHashById("DEP-1001"))
      .thenReturn(Optional.of(
        DepositFingerprint.sha256(existing)
      ));

    when(repository.findById("DEP-1001"))
      .thenReturn(Optional.of(existing));

    var result = service.create(request);

    assertFalse(result.created());
    assertEquals("MINTED", result.deposit().status());

    verify(repository).insertIfAbsent(any(Deposit.class));
    verify(repository).findPayloadHashById("DEP-1001");
    verify(repository).findById("DEP-1001");
    verifyNoMoreInteractions(repository);
  }

  @Test
  void conflictingPayloadIsRejected() {

    when(repository.insertIfAbsent(any(Deposit.class)))
      .thenReturn(false);

    when(repository.findPayloadHashById("DEP-1001"))
      .thenReturn(Optional.of("0".repeat(64)));

    assertThrows(
      DepositConflictException.class,
      () -> service.create(request)
    );

    verify(repository, never()).findById(anyString());
  }

  @Test
  void successfulStateTransitionReturnsTrue() {

    when(repository.advanceStatus(
      "DEP-1001", "RECEIVED", "VALIDATED"
    )).thenReturn(true);

    boolean advanced = service.advanceStatus(
      "DEP-1001",
      "RECEIVED",
      "VALIDATED"
    );

    assertTrue(advanced);

    verify(repository, times(1)).advanceStatus(
      "DEP-1001",
      "RECEIVED",
      "VALIDATED"
    );
  }

  @Test
  void invalidTransitionNeverTouchesRepository() {

    assertThrows(
      IllegalArgumentException.class,
      () -> service.advanceStatus(
        "DEP-1001",
        "MINTED",
        "RECEIVED"
      )
    );

    verifyNoInteractions(repository);
  }
}
