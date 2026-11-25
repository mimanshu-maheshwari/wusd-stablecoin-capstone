package com.mm.wusd.bridge.besu;

import java.math.BigInteger;

public record PreparedTransaction(
  long chainId,
  String sender,
  BigInteger nonce,
  String recipient,
  String signedHex,
  String transactionHash
) {}

