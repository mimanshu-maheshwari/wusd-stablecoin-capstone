package com.mm.wusd.bridge.besu.nonce;

import java.math.BigInteger;

public record SignedAttempt(
  long id,
  long reservationId,
  int attemptNumber,
  String transactionHash,
  String signedRawTx,
  BigInteger gasPriceWei,
  String recipient,
  BigInteger valueWei,
  BigInteger gasLimit,
  String calldata,
  String status
) {}
