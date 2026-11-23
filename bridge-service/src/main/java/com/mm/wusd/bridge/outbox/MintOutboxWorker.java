package com.mm.wusd.bridge.outbox;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class MintOutboxWorker {

  private final MintOutboxRepository repository;
  private final DemoMintWorkHandler handler;

  private final String workerId =
    "worker-" + UUID.randomUUID();

  public MintOutboxWorker(
    MintOutboxRepository repository,
    DemoMintWorkHandler handler) {

    this.repository = repository;
    this.handler = handler;
  }

  public int runOnce() {

    List<MintWorkClaim> claims =
      repository.claimBatch(
        workerId,
        10,
        Duration.ofSeconds(30)
      );

    int completed = 0;

    for (MintWorkClaim claim : claims) {

      try {
        handler.handle(claim);

        boolean success =
          repository.markDemoDone(claim);

        if (success) {
          completed++;
        } else {
          System.err.printf(
            "Lost lease before completion: id=%d token=%d%n",
            claim.id(),
            claim.leaseToken()
          );
        }

      } catch (Exception ex) {
        System.err.printf(
          "Demo handler failed: id=%d message=%s%n",
          claim.id(),
          ex.getMessage()
        );

        // Leave CLAIMED until expiry.
        // Another attempt can reclaim it.
      }
    }

    return completed;
  }
}
