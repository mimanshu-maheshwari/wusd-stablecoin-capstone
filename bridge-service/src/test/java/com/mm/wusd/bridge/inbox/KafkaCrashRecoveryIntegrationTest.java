package com.mm.wusd.bridge.inbox;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.persistence.DepositRepository;
import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KafkaCrashRecoveryIntegrationTest extends PostgresIntegrationTest {

  @Autowired
  FiatDepositRecordProcessor processor;

  @Autowired
  KafkaInboxRepository inbox;

  @Autowired
  DepositRepository deposits;

  @BeforeEach
  void cleanInbox() {
    jdbc.update("DELETE FROM kafka_inbox");
  }

  @Test
  void replayAfterCommittedDatabaseWorkIsSafe() {

    String id = "DEP-" + UUID.randomUUID();

    DepositRequest request = new DepositRequest(id, "USER-42",
      "0x1111111111111111111111111111111111111111", new BigDecimal("250.50"), "USD");

    ConsumerRecord<String, String> kafkaRecord = new ConsumerRecord<>("fiat.deposited.v1", 2,
      500L, id, "{\"depositId\":\"" + id + "\"}");

    // The transactional method returns only
    // after its database work commits.
    var first = processor.process(kafkaRecord, request);

    assertTrue(first.newInboxRecord());
    assertTrue(first.newDeposit());

    // Simulate losing the Kafka acknowledgment:
    // the same record is delivered again.
    var replay = processor.process(kafkaRecord, request);

    assertFalse(replay.newInboxRecord());
    assertFalse(replay.newDeposit());

    assertEquals(1, inbox.countByRecord("fiat.deposited.v1", 2, 500L));

    assertEquals(1, inbox.countByDepositId(id));

    assertTrue(deposits.findById(id).isPresent());
  }
}

