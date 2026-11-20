
package com.mm.wusd.bridge.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenAmountParameterizedTest {

  @ParameterizedTest(name = "{0} USD -> {1} base units")
  @CsvSource({
    "1.00, 1000000",
    "0.50, 500000",
    "250.50, 250500000",
    "0.000001, 1",
    "999999.999999, 999999999999"
  })
  @DisplayName("Converts exact amounts")
  void convertsAmountsExactly(
    String input,
    String expected) {

    BigInteger result = TokenAmount.toBaseUnits(
      new BigDecimal(input)
    );

    assertEquals(new BigInteger(expected), result);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "0",
    "-1",
    "-0.000001",
    "-250.50"
  })
  void rejectsNonPositiveAmounts(String amount) {
    assertThrows(
      IllegalArgumentException.class,
      () -> TokenAmount.toBaseUnits(
        new BigDecimal(amount)
      )
    );
  }

  @Test
  void rejectsAmountsBeyondSixDecimals() {
    assertThrows(
      ArithmeticException.class,
      () -> TokenAmount.toBaseUnits(
        new BigDecimal("10.0000001")
      )
    );
  }

  @Test
  void rejectsNull() {
    assertThrows(
      IllegalArgumentException.class,
      () -> TokenAmount.toBaseUnits(null)
    );
  }
}
