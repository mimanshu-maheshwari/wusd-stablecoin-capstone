package com.mm.wusd.bridge.outbox;

import org.springframework.stereotype.Component;

@Component
public class DemoMintWorkHandler {

  public void handle(MintWorkClaim claim) {

    // Lesson 9 only: no Besu transaction sent.
    System.out.printf(
      "DEMO mint-work handoff deposit=%s workId=%d token=%d worker=%s%n",
      claim.depositId(),
      claim.id(),
      claim.leaseToken(),
      claim.claimedBy()
    );
  }
}
