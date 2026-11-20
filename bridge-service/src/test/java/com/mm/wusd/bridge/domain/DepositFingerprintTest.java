package com.mm.wusd.bridge.domain;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class DepositFingerprintTest {

  private Deposit deposit(String wallet, BigInteger amount, String status) {

    return new Deposit("DEP-1001", "USER-42", wallet, amount, "USD", status);
  }

  @Test
  void identicalDepositsProduceSameFingerprint() {

    Deposit first = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "RECEIVED");

    Deposit second = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "RECEIVED");

    assertEquals(DepositFingerprint.sha256(first), DepositFingerprint.sha256(second));
  }

  @Test
  void statusChangesDoNotChangeFingerprint() {

    Deposit received = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "RECEIVED");

    Deposit minted = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "MINTED");

    assertEquals(DepositFingerprint.sha256(received), DepositFingerprint.sha256(minted));
  }

  @Test
  void amountChangesProduceDifferentFingerprint() {

    Deposit first = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "RECEIVED");

    Deposit second = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("500000000"), "RECEIVED");

    assertNotEquals(DepositFingerprint.sha256(first), DepositFingerprint.sha256(second));
  }

  @Test
  void walletCaseDoesNotChangeFingerprint() {

    Deposit first = deposit("0xABCD000000000000000000000000000000000001", new BigInteger("250500000"), "RECEIVED");

    Deposit second = deposit("0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "RECEIVED");

    assertEquals(DepositFingerprint.sha256(first), DepositFingerprint.sha256(second));
  }

  @Test
  void rejectsIdentifiersContainingNewlines() {

    Deposit invalid = new Deposit("DEP-1001\nFORGED", "USER-42", "0xabcd000000000000000000000000000000000001", new BigInteger("250500000"), "USD", "RECEIVED");

    assertThrows(IllegalArgumentException.class, () -> DepositFingerprint.sha256(invalid));
  }
}

