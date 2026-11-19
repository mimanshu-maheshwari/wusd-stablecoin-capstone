package com.mm.wusd.bridge.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class TokenAmountTest {

  @Test
  void convertUsdToBaseUnits() {
    // prepare
    final var input = new BigDecimal("250.50");
    final var expected = new BigInteger("250500000");
    // test
    var actual = TokenAmount.toBaseUnits(input);
    // assert
    assertEquals(expected, actual);
  }

  @Test
  void rejectsZeroAmount() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TokenAmount.toBaseUnits(
            BigDecimal.ZERO));
  }

  @Test
  void rejectsExcessiveDecimals() {
    assertThrows(
        ArithmeticException.class,
        () -> TokenAmount.toBaseUnits(
            new BigDecimal("1.0000001")));
  }

}
