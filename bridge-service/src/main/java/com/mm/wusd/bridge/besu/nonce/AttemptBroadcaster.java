package com.mm.wusd.bridge.besu.nonce;

import com.mm.wusd.bridge.besu.BesuRpcClient;
import com.mm.wusd.bridge.outbox.MintWorkClaim;
import org.springframework.stereotype.Service;

@Service
public class AttemptBroadcaster {

  private final AttemptStateRepository states;
  private final BesuRpcClient rpc;

  public AttemptBroadcaster(AttemptStateRepository states, BesuRpcClient rpc) {
    this.states = states;
    this.rpc = rpc;
  }

  // Intentionally NOT @Transactional.
  public String broadcast(MintWorkClaim claim, long attemptId) {
    SignedAttempt attempt = states.prepareBroadcast(claim, attemptId);

    try {
      String returnedHash = rpc.sendRawTransaction(attempt.signedRawTx());

      if (!attempt.transactionHash().equalsIgnoreCase(returnedHash)) {
        throw new IllegalStateException("Besu returned an unexpected tx hash");
      }

      states.markAccepted(attemptId);
      return "SUBMITTED";
    } catch (RuntimeException ex) {
      // The durable state remains BROADCAST_UNKNOWN
      // or SUBMITTED if acknowledgment was saved.
      // Do not create another nonce or transaction.
      return "UNCERTAIN";
    }
  }
}

