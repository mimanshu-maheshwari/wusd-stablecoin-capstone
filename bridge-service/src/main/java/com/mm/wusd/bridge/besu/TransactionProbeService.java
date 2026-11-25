package com.mm.wusd.bridge.besu;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

@Service
public class TransactionProbeService {

  private final BesuRpcClient rpc;
  private final TransactionJournal journal;

  public TransactionProbeService(BesuRpcClient rpc, TransactionJournal journal) {

    this.rpc = rpc;
    this.journal = journal;
  }

  public void submit(PreparedTransaction tx) {

    // Durable local record must exist
    // before this method is called.
    try {
      String returnedHash = rpc.sendRawTransaction(tx.signedHex());

      if (!tx.transactionHash().equalsIgnoreCase(returnedHash)) {
        journal.markUncertain(tx.transactionHash(), "RPC returned a different transaction hash");
        throw new IllegalStateException("Unexpected RPC transaction hash");
      }

      journal.markAccepted(tx.transactionHash());

    } catch (BesuRpcClient.RpcTransportException ex) {

      journal.markUncertain(tx.transactionHash(), ex.getMessage());

      // Do not claim failure.
      // The node may have received the transaction.
      throw ex;

    } catch (BesuRpcClient.RpcResponseException ex) {

      journal.markUncertain(tx.transactionHash(), ex.getMessage());

      // Even an RPC error requires reconciliation
      // when previous broadcasts may have occurred.
      throw ex;
    }
  }

  public String reconcile(String transactionHash) {

    JsonNode receipt = rpc.receipt(transactionHash);

    if (receipt.isNull()) {

      JsonNode known = rpc.transactionByHash(transactionHash);

      return known.isNull() ? "NOT_CURRENTLY_VISIBLE" : "PENDING";
    }

    String status = receipt.path("status").asText();
    String blockHash = receipt.path("blockHash").asText();
    String blockNumberHex = receipt.path("blockNumber").asText();

    if (!"0x1".equals(status) && !"0x0".equals(status)) {

      throw new IllegalStateException("Unsupported receipt status: " + status);
    }

    // Verify that the receipt references the
    // block currently returned at this height.
    JsonNode canonicalBlock = rpc.blockByNumber(blockNumberHex);

    if (canonicalBlock.isNull()) {
      return "RECHECK_REQUIRED";
    }

    String canonicalHash = canonicalBlock.path("hash").asText();

    if (!blockHash.equalsIgnoreCase(canonicalHash)) {
      return "RECHECK_REQUIRED";
    }

    long blockNumber = BesuRpcClient.hexQuantity(blockNumberHex).longValueExact();

    boolean succeeded = "0x1".equals(status);

    journal.recordReceipt(transactionHash, blockHash.toLowerCase(Locale.ROOT), blockNumber,
      succeeded);

    return succeeded ? "INCLUDED_SUCCESS" : "INCLUDED_REVERTED";
  }
}
