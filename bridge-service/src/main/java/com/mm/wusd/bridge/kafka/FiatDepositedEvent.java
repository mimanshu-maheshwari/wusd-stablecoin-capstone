package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.api.DepositRequest;

import java.math.BigDecimal;

public record FiatDepositedEvent(
  String eventType,
  String depositId,
  String userId,
  String walletAddress,
  BigDecimal amount,
  String currency
) {

  public static FiatDepositedEvent from(
    DepositRequest request) {

    return new FiatDepositedEvent(
      "FiatDeposited",
      request.depositId(),
      request.userId(),
      request.walletAddress(),
      request.amount(),
      request.currency()
    );
  }

  public DepositRequest toDepositRequest() {
    return new DepositRequest(
      depositId,
      userId,
      walletAddress,
      amount,
      currency
    );
  }
}
