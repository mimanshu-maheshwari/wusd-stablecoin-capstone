package com.mm.wusd.bridge.besu.nonce;

import java.math.BigInteger;

public record NonceReservation(
  long id,
  long outboxId,
  long chainId,
  String signerAddress,
  BigInteger nonce,
  String status
) {
}

