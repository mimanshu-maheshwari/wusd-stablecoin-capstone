
package com.mm.wusd.bridge.besu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
  "spring.kafka.listener.auto-startup=false",
  "spring.kafka.listener.ack-mode=manual_immediate",
  "spring.kafka.admin.auto-create=false",
  "wusd.outbox.worker.enabled=false"
})
class TransactionJournalIntegrationTest {

  static final PostgreSQLContainer POSTGRES =
    new PostgreSQLContainer("postgres:17")
      .withDatabaseName("wusd_besu_it")
      .withUsername("test")
      .withPassword("test");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {

    registry.add(
      "spring.datasource.url",
      POSTGRES::getJdbcUrl
    );

    registry.add(
      "spring.datasource.username",
      POSTGRES::getUsername
    );

    registry.add(
      "spring.datasource.password",
      POSTGRES::getPassword
    );
  }

  @Autowired
  TransactionJournal journal;

  private PreparedTransaction tx(
    BigInteger nonce) {

    String hash =
      "0x" + UUID.randomUUID()
        .toString().replace("-", "")
        + UUID.randomUUID()
        .toString().replace("-", "");

    return new PreparedTransaction(
      1337L,
      "0x1111111111111111111111111111111111111111",
      nonce,
      "0x2222222222222222222222222222222222222222",
      "0xdeadbeef",
      hash
    );
  }

  @Test
  void signedTransactionIsPersistedBeforeBroadcast() {

    PreparedTransaction prepared =
      tx(BigInteger.valueOf(1001));

    journal.saveSigned(prepared);

    assertEquals(
      "SIGNED",
      journal.status(prepared.transactionHash())
    );
  }

  @Test
  void duplicateNonceReservationIsRejected() {

    PreparedTransaction first =
      tx(BigInteger.valueOf(1002));

    PreparedTransaction second =
      tx(BigInteger.valueOf(1002));

    journal.saveSigned(first);

    assertThrows(
      org.springframework.dao.DataIntegrityViolationException.class,
      () -> journal.saveSigned(second)
    );
  }

  @Test
  void acceptedSubmissionIsNotIncludedSuccess() {

    PreparedTransaction prepared =
      tx(BigInteger.valueOf(1003));

    journal.saveSigned(prepared);

    assertTrue(
      journal.markAccepted(prepared.transactionHash())
    );

    assertEquals(
      "SUBMISSION_ACCEPTED",
      journal.status(prepared.transactionHash())
    );

    assertNotEquals(
      "INCLUDED_SUCCESS",
      journal.status(prepared.transactionHash())
    );
  }

  @Test
  void transportUncertaintyDoesNotMeanFailure() {

    PreparedTransaction prepared =
      tx(BigInteger.valueOf(1004));

    journal.saveSigned(prepared);

    assertTrue(
      journal.markUncertain(
        prepared.transactionHash(),
        "RPC response lost"
      )
    );

    assertEquals(
      "UNCERTAIN",
      journal.status(prepared.transactionHash())
    );
  }

  @Test
  void receiptRecordsExecutionOutcome() {

    PreparedTransaction prepared =
      tx(BigInteger.valueOf(1005));

    journal.saveSigned(prepared);
    journal.markAccepted(prepared.transactionHash());

    assertTrue(
      journal.recordReceipt(
        prepared.transactionHash(),
        "0x" + "a".repeat(64),
        25,
        true
      )
    );

    assertEquals(
      "INCLUDED_SUCCESS",
      journal.status(prepared.transactionHash())
    );

    // Older submission reports must not downgrade
    // an observed inclusion result.
    assertFalse(
      journal.markUncertain(
        prepared.transactionHash(),
        "Late timeout notification"
      )
    );
  }
}
