package com.mm.wusd.bridge.api;

import com.mm.wusd.bridge.domain.Deposit;

import java.math.BigInteger;

public record DepositResponse(
    String depositId,
    String userId,
    String walletAddress,
    BigInteger amountUnits,
    String currency,
    String status) {
  public static DepositResponse from(Deposit deposit) {
    return new DepositResponse(
        deposit.depositId(),
        deposit.userId(),
        deposit.walletAddress(),
        deposit.amountUnits(),
        deposit.currency(),
        deposit.status());
  }
}
