package com.mm.wusd.bridge.besu;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.web3j.crypto.Credentials;

import java.math.BigInteger;

public final class BesuProbeMain {

  private BesuProbeMain() {
  }

  public static void main(String[] args) {

    // Fail closed unless explicitly enabled.
    if (!"true".equalsIgnoreCase(
      System.getenv("WUSD_ENABLE_TX_PROBE"))) {

      throw new IllegalStateException(
        "Set WUSD_ENABLE_TX_PROBE=true for this local-only experiment"
      );
    }

    String privateKey = System.getenv("WUSD_DEMO_PRIVATE_KEY");

    if (privateKey == null || privateKey.isBlank()) {
      throw new IllegalStateException("Missing WUSD_DEMO_PRIVATE_KEY");
    }

    try (ConfigurableApplicationContext context =
           SpringApplication.run(
             com.mm.wusd.bridge.BridgeServiceApplication.class,
             "--spring.kafka.listener.auto-startup=false",
             "--wusd.outbox.worker.enabled=false"
           )) {

      BesuRpcClient rpc = context.getBean(BesuRpcClient.class);
      TransactionJournal journal = context.getBean(TransactionJournal.class);
      TransactionProbeService probe = context.getBean(TransactionProbeService.class);

      long chainId = rpc.chainId();

      if (chainId != 1337L) {
        throw new IllegalStateException("Wrong chain ID: " + chainId);
      }

      String sender = Credentials.create(privateKey).getAddress();

      String recipient = "0x627306090abaB3A6e1400e9345bC60c78a8BEf57";

      if (rpc.balance(sender).signum() <= 0) {
        throw new IllegalStateException("Demo sender is not funded on this chain");
      }

      // FOR A SINGLE LOCAL TEST SENDER ONLY.
      // Never use this as a three-pod nonce allocator.
      BigInteger nonce = rpc.transactionCount(sender, "pending");

      BigInteger gasPrice = rpc.gasPrice();

      PreparedTransaction tx =
        DemoTransactionSigner.prepareTransfer(
          privateKey,
          chainId,
          nonce,
          gasPrice,
          recipient,
          BigInteger.ONE
        );

      // Persist BEFORE broadcasting.
      journal.saveSigned(tx);

      System.out.println("Prepared transaction hash: " + tx.transactionHash());
      System.out.println("Sender nonce: " + tx.nonce());

      try {
        probe.submit(tx);
        System.out.println("RPC submission acknowledged");

      } catch (RuntimeException ex) {
        System.out.println("Submission not confirmed: " + ex.getMessage());
      }

      // One immediate check; may still be pending.
      try {
        String state = probe.reconcile(tx.transactionHash());
        System.out.println("Reconciliation result: " + state);

      } catch (RuntimeException ex) {
        System.out.println("Reconciliation needs retry: " + ex.getMessage());
      }
    }
  }
}
