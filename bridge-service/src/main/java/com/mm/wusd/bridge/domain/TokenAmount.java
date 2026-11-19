package com.mm.wusd.bridge.domain;

import java.math.BigDecimal;
import java.math.BigInteger;

public final record TokenAmount() {

  private static final int DECIMALS = 6;

  public static BigInteger toBaseUnits(BigDecimal amount) {
    if (null == amount || amount.signum() <= 0) {
      throw new IllegalArgumentException("Amount must be positive.");
    }

    return amount.movePointRight(DECIMALS).toBigIntegerExact();
  }

}
