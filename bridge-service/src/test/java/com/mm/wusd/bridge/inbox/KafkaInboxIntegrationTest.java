package com.mm.wusd.bridge.inbox;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.exception.DepositConflictException;
import com.mm.wusd.bridge.persistence.DepositRepository;
import com.mm.wusd.bridge.support.PostgresIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KafkaInboxIntegrationTest
  extends PostgresIntegrationTest {

  @Autowired
  private FiatDepositRecordProcessor processor;

  @Autowired
  private KafkaInboxRepository inbox;

  @Autowired
  private DepositRepository deposits;

  @BeforeEach
  void cleanInbox() {
    jdbc.update("DELETE FROM kafka_inbox");
  }

  private DepositRequest request(
    String id,
    String amount) {

    return new DepositRequest(
      id,
      "USER-42",
      "0x1111111111111111111111111111111111111111",
      new BigDecimal(amount),
      "USD"
    );
  }

  private ConsumerRecord<String, String> record(
    String id,
    long offset,
    String amount) {

    String value = """
      {
        "eventType":"FiatDeposited",
        "depositId":"%s",
        "userId":"USER-42",
        "walletAddress":"0x1111111111111111111111111111111111111111",
        "amount":%s,
        "currency":"USD"
      }
      """.formatted(id, amount);

    return new ConsumerRecord<>(
      "fiat.deposited.v1",
      3,
      offset,
      id,
      value
    );
  }

  @Test
  void createsInboxAndDepositTogether() {

    String id = "DEP-" + UUID.randomUUID();

    var result = processor.process(
      record(id, 100L, "250.50"),
      request(id, "250.50")
    );

    assertTrue(result.newInboxRecord());
    assertTrue(result.newDeposit());

    assertEquals(1, inbox.countByDepositId(id));
    assertTrue(deposits.findById(id).isPresent());
  }

  @Test
  void replayOfSameKafkaRecordDoesNotInsertAgain() {

    String id = "DEP-" + UUID.randomUUID();

    var kafkaRecord = record(
      id, 101L, "100.00"
    );

    var request = request(id, "100.00");

    var first = processor.process(
      kafkaRecord,
      request
    );

    var replay = processor.process(
      kafkaRecord,
      request
    );

    assertTrue(first.newInboxRecord());
    assertFalse(replay.newInboxRecord());

    assertEquals(1, inbox.countByDepositId(id));
    assertEquals(
      1,
      inbox.countByRecord(
        "fiat.deposited.v1", 3, 101L
      )
    );
  }

  @Test
  void separateKafkaRecordsCanReferenceSameDeposit() {

    String id = "DEP-" + UUID.randomUUID();

    var first = processor.process(
      record(id, 102L, "100.00"),
      request(id, "100.00")
    );

    var duplicate = processor.process(
      record(id, 103L, "100.00"),
      request(id, "100.00")
    );

    assertTrue(first.newDeposit());
    assertTrue(duplicate.newInboxRecord());
    assertFalse(duplicate.newDeposit());

    assertEquals(2, inbox.countByDepositId(id));
    assertTrue(deposits.findById(id).isPresent());
  }

  @Test
  void conflictingPayloadRollsBackInboxInsert() {

    String id = "DEP-" + UUID.randomUUID();

    processor.process(
      record(id, 104L, "100.00"),
      request(id, "100.00")
    );

    assertThrows(
      DepositConflictException.class,
      () -> processor.process(
        record(id, 105L, "500.00"),
        request(id, "500.00")
      )
    );

    assertEquals(
      0,
      inbox.countByRecord(
        "fiat.deposited.v1", 3, 105L
      )
    );

    assertEquals(
      "100000000",
      deposits.findById(id)
        .orElseThrow()
        .amountUnits()
        .toString()
    );
  }

  @Test
  void failedDepositInsertDoesNotLeaveInboxRow() {

    String id = "DEP-" + UUID.randomUUID();

    DepositRequest invalid = request(
      id, "-10.00"
    );

    assertThrows(
      IllegalArgumentException.class,
      () -> processor.process(
        record(id, 106L, "-10.00"),
        invalid
      )
    );

    assertEquals(
      0,
      inbox.countByRecord(
        "fiat.deposited.v1", 3, 106L
      )
    );

    assertTrue(deposits.findById(id).isEmpty());
  }
}

