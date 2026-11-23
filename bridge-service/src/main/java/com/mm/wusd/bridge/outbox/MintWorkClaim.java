package com.mm.wusd.bridge.outbox;

import java.time.Instant;

public record MintWorkClaim(
  long id,
  String depositId,
  long leaseToken,
  String claimedBy,
  Instant leaseExpiresAt
) {
}
