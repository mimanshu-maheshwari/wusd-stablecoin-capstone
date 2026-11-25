
package com.mm.wusd.bridge.besu;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TransactionProbeServiceTest {

  private final BesuRpcClient rpc =
    mock(BesuRpcClient.class);

  private final TransactionJournal journal =
    mock(TransactionJournal.class);

  private final TransactionProbeService service =
    new TransactionProbeService(rpc, journal);

  private final PreparedTransaction tx =
    new PreparedTransaction(
      1337,
      "0x1111111111111111111111111111111111111111",
      BigInteger.ZERO,
      "0x2222222222222222222222222222222222222222",
      "0xdeadbeef",
      "0x" + "a".repeat(64)
    );

  @Test
  void successfulBroadcastDoesNotRecordReceipt() {

    when(rpc.sendRawTransaction(tx.signedHex()))
      .thenReturn(tx.transactionHash());

    service.submit(tx);

    verify(journal).markAccepted(
      tx.transactionHash()
    );

    verify(journal, never()).recordReceipt(
      anyString(),
      anyString(),
      anyLong(),
      anyBoolean()
    );
  }

  @Test
  void transportTimeoutCreatesUncertainOutcome() {

    when(rpc.sendRawTransaction(tx.signedHex()))
      .thenThrow(
        new BesuRpcClient.RpcTransportException(
          "Simulated timeout"
        )
      );

    assertThrows(
      BesuRpcClient.RpcTransportException.class,
      () -> service.submit(tx)
    );

    verify(journal).markUncertain(
      tx.transactionHash(),
      "Simulated timeout"
    );

    verify(journal, never()).markAccepted(
      anyString()
    );
  }

  @Test
  void pendingReceiptDoesNotMarkSuccess() {

    JsonMapper mapper =
      JsonMapper.builder().build();

    when(rpc.receipt(tx.transactionHash()))
      .thenReturn(mapper.readTree("null"));

    when(rpc.transactionByHash(tx.transactionHash()))
      .thenReturn(
        mapper.readTree(
          "{\"hash\":\"" + tx.transactionHash() + "\"}"
        )
      );

    assertEquals(
      "PENDING",
      service.reconcile(tx.transactionHash())
    );

    verify(journal, never()).recordReceipt(
      anyString(),
      anyString(),
      anyLong(),
      anyBoolean()
    );
  }

  @Test
  void successfulReceiptIsRecordedOnlyAfterBlockCheck() {

    JsonMapper mapper =
      JsonMapper.builder().build();

    String blockHash = "0x" + "b".repeat(64);

    when(rpc.receipt(tx.transactionHash()))
      .thenReturn(mapper.readTree("""
        {
          "status": "0x1",
          "blockHash": "%s",
          "blockNumber": "0x19"
        }
        """.formatted(blockHash)));

    when(rpc.blockByNumber("0x19"))
      .thenReturn(mapper.readTree("""
        {
          "hash": "%s"
        }
        """.formatted(blockHash)));

    assertEquals(
      "INCLUDED_SUCCESS",
      service.reconcile(tx.transactionHash())
    );

    verify(journal).recordReceipt(
      tx.transactionHash(),
      blockHash,
      25,
      true
    );
  }
}
