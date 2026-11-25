
package com.mm.wusd.bridge.besu;

import org.junit.jupiter.api.Test;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class DemoTransactionSignerTest {

  // Public Besu development account. Test only.
  private static final String DEMO_KEY =
    "8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63";

  @Test
  void signsTransactionForExpectedChain() {

    PreparedTransaction tx =
      DemoTransactionSigner.prepareTransfer(
        DEMO_KEY,
        1337L,
        BigInteger.ZERO,
        BigInteger.ZERO,
        "0x627306090abaB3A6e1400e9345bC60c78a8BEf57",
        BigInteger.ONE
      );

    assertEquals(1337L, tx.chainId());
    assertEquals(BigInteger.ZERO, tx.nonce());

    assertEquals(
      "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73",
      tx.sender().toLowerCase()
    );

    assertTrue(tx.signedHex().startsWith("0x"));

    assertEquals(
      tx.transactionHash(),
      Numeric.toHexString(
        Hash.sha3(
          Numeric.hexStringToByteArray(
            tx.signedHex()
          )
        )
      )
    );
  }

  @Test
  void rejectsUnexpectedChainId() {

    assertThrows(
      IllegalArgumentException.class,
      () -> DemoTransactionSigner.prepareTransfer(
        DEMO_KEY,
        1L,
        BigInteger.ZERO,
        BigInteger.ZERO,
        "0x627306090abaB3A6e1400e9345bC60c78a8BEf57",
        BigInteger.ONE
      )
    );
  }
}
