package com.mm.wusd.bridge.besu.nonce;

import com.mm.wusd.bridge.besu.BesuRpcClient;
import com.mm.wusd.bridge.outbox.MintOutboxRepository;
import com.mm.wusd.bridge.outbox.MintWorkClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(
  properties = {
    "spring.kafka.listener.auto-startup=false", "spring.kafka.listener" +
                                                ".ack-mode=manual_immediate",
    "spring.kafka.admin.auto-create=false", "wusd.outbox.worker.enabled=false"
  }
)
class DistributedNonceIntegrationTest {

  private static final String SIGNER =
    "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73";

  private static final String DEMO_KEY =
    "8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63";

  private static final String RECIPIENT =
    "0x627306090abaB3A6e1400e9345bC60c78a8BEf57";

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
    "postgres:17").withDatabaseName("wusd_nonce_it").withUsername(
    "test").withPassword("test");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MintOutboxRepository outbox;

  @Autowired
  DistributedNonceAllocator allocator;

  @Autowired
  DurableSigningService signing;

  @Autowired
  AttemptStateRepository attemptStates;

  @BeforeEach
  void resetDatabase() {

    jdbc.update("DELETE FROM signed_transaction_attempt");
    jdbc.update("DELETE FROM signer_nonce_reservation");
    jdbc.update("DELETE FROM signer_nonce_cursor");

    jdbc.update("DELETE FROM mint_outbox");
    jdbc.update("DELETE FROM kafka_inbox");
    jdbc.update("DELETE FROM mint_operations");

    // Artificial starting nonce. This test does not
    // contact a blockchain or use real funds.
    jdbc.update(
      """
        INSERT INTO signer_nonce_cursor(
            chain_id, signer_address, next_nonce
        )
        VALUES (1337, ?, 100)
        """, SIGNER
    );
  }

  private long nextNonce() {
    return jdbc.queryForObject(
      """
        SELECT next_nonce
        FROM signer_nonce_cursor
        WHERE chain_id = 1337
          AND signer_address = ?
        """, BigDecimal.class, SIGNER
    ).longValueExact();
  }

  private MintWorkClaim claimOne(String worker) {

    String depositId = "DEP-" + UUID.randomUUID();

    assertTrue(outbox.enqueue(depositId));

    List<MintWorkClaim> claims = outbox.claimBatch(
      worker, 1,
      Duration.ofSeconds(120)
    );

    assertEquals(1, claims.size());

    return claims.getFirst();
  }

  @Test
  void reservationIsDurableAndIdempotent() {

    MintWorkClaim claim = claimOne("pod-A");

    var first = allocator.reserve(claim, 1337, SIGNER);

    var retry = allocator.reserve(claim, 1337, SIGNER);

    assertEquals(first.id(), retry.id());
    assertEquals(first.nonce(), retry.nonce());
    assertEquals(BigInteger.valueOf(100), first.nonce());

    assertEquals(101, nextNonce());
  }

  @Test
  void staleWorkerCannotAllocateNonce() {

    MintWorkClaim claim = claimOne("pod-A");

    jdbc.update(
      """
        UPDATE mint_outbox
        SET lease_expires_at =
            now() - INTERVAL '1 second'
        WHERE id = ?
        """, claim.id()
    );

    assertThrows(
      IllegalStateException.class,
      () -> allocator.reserve(claim, 1337, SIGNER)
    );

    assertEquals(100, nextNonce());
  }

  @Test
  void threePodsAllocateUniqueNonces() throws Exception {

    List<MintWorkClaim> claims = new ArrayList<>();

    // Create 24 available work items.
    for (int i = 0; i < 24; i++) {
      assertTrue(outbox.enqueue("DEP-" + UUID.randomUUID()));
    }

    for (int pod = 0; pod < 3; pod++) {
      claims.addAll(
        outbox.claimBatch("pod-" + pod, 8, Duration.ofSeconds(120)));
    }

    assertEquals(24, claims.size());

    CountDownLatch ready = new CountDownLatch(3);

    CountDownLatch start = new CountDownLatch(1);

    try (
      ExecutorService pool = Executors.newFixedThreadPool(3)
    ) {

      List<Future<List<BigInteger>>> futures = new ArrayList<>();

      for (int pod = 0; pod < 3; pod++) {

        final int index = pod;

        futures.add(pool.submit(() -> {

          ready.countDown();

          if (!start.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Start signal timed out");
          }

          List<BigInteger> result = new ArrayList<>();

          int begin = index * 8;

          for (int j = begin; j < begin + 8; j++) {

            result.add(allocator.reserve(claims.get(j), 1337, SIGNER).nonce());
          }

          return result;
        }));
      }

      assertTrue(ready.await(15, TimeUnit.SECONDS));

      start.countDown();

      Set<BigInteger> unique = new HashSet<>();

      for (Future<List<BigInteger>> future : futures) {
        for (BigInteger nonce : future.get(30, TimeUnit.SECONDS)) {

          assertTrue(unique.add(nonce), "Duplicate nonce: " + nonce);
        }
      }

      assertEquals(24, unique.size());

      for (long nonce = 100; nonce < 124; nonce++) {
        assertTrue(unique.contains(BigInteger.valueOf(nonce)));
      }

      assertEquals(124, nextNonce());
    }
  }

  @Test
  void crashAfterReservationReusesSameNonce() {

    MintWorkClaim firstClaim = claimOne("pod-A");

    NonceReservation reserved = allocator.reserve(firstClaim, 1337, SIGNER);

    // Simulate pod death and lease expiry.
    jdbc.update(
      """
        UPDATE mint_outbox
        SET lease_expires_at =
            now() - INTERVAL '1 second'
        WHERE id = ?
        """, firstClaim.id()
    );

    MintWorkClaim recoveredClaim = outbox.claimBatch(
      "pod-B", 1,
      Duration.ofSeconds(120)
    ).getFirst();

    NonceReservation recovered = allocator.reserve(
      recoveredClaim, 1337, SIGNER);

    assertEquals(reserved.id(), recovered.id());
    assertEquals(reserved.nonce(), recovered.nonce());
    assertEquals(101, nextNonce());
  }

  @Test
  void crashAfterSigningReusesExactSignedTransaction() {

    MintWorkClaim firstClaim = claimOne("pod-A");

    allocator.reserve(firstClaim, 1337, SIGNER);

    SignedAttempt first = signing.signFirst(
      firstClaim, DEMO_KEY, RECIPIENT,
      BigInteger.ONE,
      BigInteger.valueOf(1_000_000_000)
    );

    jdbc.update(
      """
        UPDATE mint_outbox
        SET lease_expires_at =
            now() - INTERVAL '1 second'
        WHERE id = ?
        """, firstClaim.id()
    );

    MintWorkClaim recoveredClaim = outbox.claimBatch(
      "pod-B", 1,
      Duration.ofSeconds(120)
    ).getFirst();

    SignedAttempt recovered = signing.signFirst(
      recoveredClaim, DEMO_KEY, RECIPIENT, BigInteger.ONE,
      BigInteger.valueOf(1_000_000_000)
    );

    assertEquals(first.transactionHash(), recovered.transactionHash());

    assertEquals(first.signedRawTx(), recovered.signedRawTx());

    assertEquals(first.id(), recovered.id());
    assertEquals(101, nextNonce());
  }

  @Test
  void replacementKeepsNonceAndIncreasesPrice() {

    MintWorkClaim claim = claimOne("pod-A");

    NonceReservation reservation = allocator.reserve(claim, 1337, SIGNER);

    SignedAttempt original = signing.signFirst(
      claim, DEMO_KEY, RECIPIENT,
      BigInteger.ONE,
      BigInteger.valueOf(100_000_000)
    );

    SignedAttempt replacement = signing.prepareReplacement(
      claim, DEMO_KEY,
      BigInteger.valueOf(
        115_000_000)
    );

    assertEquals(original.reservationId(), replacement.reservationId());

    assertEquals(1, original.attemptNumber());
    assertEquals(2, replacement.attemptNumber());

    assertNotEquals(original.transactionHash(), replacement.transactionHash());

    assertEquals(original.recipient(), replacement.recipient());

    assertEquals(original.valueWei(), replacement.valueWei());

    assertEquals(BigInteger.valueOf(100), reservation.nonce());

    assertEquals(101, nextNonce());
  }

  @Test
  void rpcTimeoutPreservesSignedTransactionAndNonce() {

    MintWorkClaim claim = claimOne("pod-A");

    allocator.reserve(claim, 1337, SIGNER);

    SignedAttempt signed = signing.signFirst(
      claim, DEMO_KEY, RECIPIENT,
      BigInteger.ONE,
      BigInteger.valueOf(1_000_000_000)
    );

    BesuRpcClient rpc = mock(BesuRpcClient.class);

    when(rpc.sendRawTransaction(signed.signedRawTx())).thenThrow(
      new BesuRpcClient.RpcTransportException("Simulated lost RPC response"));

    AttemptBroadcaster broadcaster = new AttemptBroadcaster(attemptStates, rpc);

    String outcome = broadcaster.broadcast(claim, signed.id());

    assertEquals("UNCERTAIN", outcome);

    String state = jdbc.queryForObject(
      """
        SELECT status
        FROM signer_nonce_reservation
        WHERE id = ?
        """, String.class, signed.reservationId()
    );

    assertEquals("BROADCAST_UNKNOWN", state);

    String storedHash = jdbc.queryForObject(
      """
        SELECT transaction_hash
        FROM signed_transaction_attempt
        WHERE id = ?
        """, String.class, signed.id()
    );

    assertEquals(signed.transactionHash(), storedHash);

    assertEquals(101, nextNonce());

    verify(rpc, times(1)).sendRawTransaction(signed.signedRawTx());
  }

  @Test
  void crashBeforeRpcLeavesRecoverableSignedBytes() {

    MintWorkClaim claim = claimOne("pod-A");

    allocator.reserve(claim, 1337, SIGNER);

    SignedAttempt signed = signing.signFirst(
      claim, DEMO_KEY, RECIPIENT,
      BigInteger.ONE,
      BigInteger.valueOf(1_000_000_000)
    );

    attemptStates.prepareBroadcast(claim, signed.id());

    // Simulate a crash here. No RPC invocation.
    String status = jdbc.queryForObject(
      """
        SELECT status
        FROM signed_transaction_attempt
        WHERE id = ?
        """, String.class, signed.id()
    );

    assertEquals("BROADCAST_UNKNOWN", status);

    String rawTx = jdbc.queryForObject(
      """
        SELECT signed_raw_tx
        FROM signed_transaction_attempt
        WHERE id = ?
        """, String.class, signed.id()
    );

    assertEquals(signed.signedRawTx(), rawTx);

    // The original nonce remains allocated.
    assertEquals(101, nextNonce());
  }

  @Test
  void concurrentRetriesOfOneJobReturnOneReservation() throws Exception {

    MintWorkClaim claim = claimOne("pod-A");

    CountDownLatch ready = new CountDownLatch(3);
    CountDownLatch start = new CountDownLatch(1);

    try (
      ExecutorService pool = Executors.newFixedThreadPool(3)
    ) {

      List<Future<NonceReservation>> futures = new ArrayList<>();

      for (int i = 0; i < 3; i++) {
        futures.add(pool.submit(() -> {
          ready.countDown();

          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Start timed out");
          }

          return allocator.reserve(claim, 1337, SIGNER);
        }));
      }

      assertTrue(ready.await(10, TimeUnit.SECONDS));

      start.countDown();

      Set<Long> reservationIds = new HashSet<>();

      for (Future<NonceReservation> future : futures) {
        reservationIds.add(future.get(20, TimeUnit.SECONDS).id());
      }

      assertEquals(1, reservationIds.size());
      assertEquals(101, nextNonce());
    }
  }

  @Test
  void signingFailureKeepsExistingReservation() {

    MintWorkClaim claim = claimOne("pod-A");

    NonceReservation reservation = allocator.reserve(claim, 1337, SIGNER);

    assertThrows(
      RuntimeException.class,
      () -> signing.signFirst(
        claim, "not-a-private-key", RECIPIENT, BigInteger.ONE,
        BigInteger.valueOf(1_000_000_000)
      )
    );

    Long attemptCount = jdbc.queryForObject(
      """
        SELECT COUNT(*)
        FROM signed_transaction_attempt
        WHERE reservation_id = ?
        """, Long.class, reservation.id()
    );

    assertEquals(0L, attemptCount);

    assertEquals(BigInteger.valueOf(100), reservation.nonce());

    assertEquals(101, nextNonce());
  }

}

