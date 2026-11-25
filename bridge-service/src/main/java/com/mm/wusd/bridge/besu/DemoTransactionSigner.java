package com.mm.wusd.bridge.besu;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

public final class DemoTransactionSigner {

  private DemoTransactionSigner() {
  }

  public static PreparedTransaction prepareTransfer(
    String privateKey,
    long chainId,
    BigInteger nonce,
    BigInteger gasPrice,
    String recipient,
    BigInteger amountWei) {

    if (chainId != 1337L) {
      throw new IllegalArgumentException("This demo only supports chain ID 1337");
    }

    Credentials credentials = Credentials.create(privateKey);

    RawTransaction unsigned =
      RawTransaction.createEtherTransaction(
        nonce,
        gasPrice,
        BigInteger.valueOf(21_000L),
        recipient,
        amountWei
      );

    byte[] signedBytes =
      TransactionEncoder.signMessage(
        unsigned,
        chainId,
        credentials
      );

    String signedHex = Numeric.toHexString(signedBytes);

    String transactionHash = Numeric.toHexString(Hash.sha3(signedBytes));

    return new PreparedTransaction(
      chainId,
      credentials.getAddress(),
      nonce,
      recipient,
      signedHex,
      transactionHash
    );
  }
}
