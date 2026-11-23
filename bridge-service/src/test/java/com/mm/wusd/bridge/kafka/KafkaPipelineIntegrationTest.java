package com.mm.wusd.bridge.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
  "spring.kafka.listener.auto-startup=true",
  "spring.kafka.listener.ack-mode=manual_immediate",
  "spring.kafka.admin.auto-create=true",
  "spring.kafka.listener.concurrency=1",
  "spring.kafka.consumer.group-id=wusd-bridge-integration-test",
  "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Import(KafkaPipelineIntegrationTest.TestTopics.class)
class KafkaPipelineIntegrationTest {

  static final PostgreSQLContainer POSTGRES =
    new PostgreSQLContainer("postgres:17").
      withDatabaseName("wusd_kafka_it")
      .withUsername("test")
      .withPassword("test");

  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @Autowired
  org.springframework.kafka.config.KafkaListenerEndpointRegistry
    listenerRegistry;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {

    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);

    registry.add("spring.datasource.username", POSTGRES::getUsername);

    registry.add("spring.datasource.password", POSTGRES::getPassword);

    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestTopics {

    @Bean
    KafkaAdmin.NewTopics testTopics() {
      return new KafkaAdmin.NewTopics(TopicBuilder.name("fiat.deposited.v1").partitions(6).replicas(1).build(),

        TopicBuilder.name("fiat.deposited.v1-dlt").partitions(6).replicas(1).build());
    }
  }

  @Autowired
  KafkaTemplate<String, String> kafkaTemplate;

  @Autowired
  JdbcTemplate jdbc;

  @Test
  void kafkaListenerIsActuallyRunning() {

    var containers =
      listenerRegistry.getListenerContainers();

    assertFalse(
      containers.isEmpty(),
      "No Kafka listener containers registered"
    );

    containers.forEach(container -> {
      System.out.println(
        "Listener running: " + container.isRunning()
      );

      System.out.println(
        "Listener assigned partitions: "
          + container.getAssignedPartitions()
      );

      assertTrue(
        container.isRunning(),
        "Kafka listener is not running"
      );
    });
  }

  @Test
  void validDepositIsPersistedByKafkaListener() throws Exception {

    String id = "DEP-" + UUID.randomUUID();
    System.out.println(
      "Publishing depositId=" + id
        + " to " + KAFKA.getBootstrapServers()
    );


    var sendResult = kafkaTemplate.send("fiat.deposited.v1", id, validJson(id, "250.50")).get(15,
      TimeUnit.SECONDS);

    System.out.println(
      "Published partition="
        + sendResult.getRecordMetadata().partition()
        + " offset="
        + sendResult.getRecordMetadata().offset()
    );

    awaitCondition(() -> count("SELECT COUNT(*) FROM mint_operations WHERE deposit_id = ?", id) == 1);

    assertEquals(1, count("SELECT COUNT(*) FROM kafka_inbox WHERE deposit_id = ?", id));
  }

  @Test
  void conflictingDepositGoesToDeadLetterTopic() throws Exception {

    String id = "DEP-" + UUID.randomUUID();

    kafkaTemplate.send("fiat.deposited.v1", id, validJson(id, "100.00")).get(15, TimeUnit.SECONDS);

    awaitCondition(() -> count("SELECT COUNT(*) FROM mint_operations WHERE deposit_id = ?", id) == 1);

    kafkaTemplate.send("fiat.deposited.v1", id, validJson(id, "500.00")).get(15, TimeUnit.SECONDS);

    try (KafkaConsumer<String, String> consumer =
           new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
             KAFKA.getBootstrapServers(),

      ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID(),

      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",

      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,

      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {

      consumer.subscribe(List.of("fiat.deposited.v1-dlt"));

      boolean found = false;

      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();

      while (System.nanoTime() < deadline) {

        var records = consumer.poll(Duration.ofMillis(500));

        for (var record : records) {
          if (id.equals(record.key())) {
            found = true;
            assertTrue(record.value().contains("500.00"));
          }
        }

        if (found) {
          break;
        }
      }

      assertTrue(found, "Conflicting event not found in DLT");
    }

    assertEquals(1, count("SELECT COUNT(*) FROM mint_operations WHERE deposit_id = ?", id));
  }

  private long count(String sql, String id) {
    Long count = jdbc.queryForObject(sql, Long.class, id);

    return count == null ? 0 : count;
  }

  private static String validJson(String id, String amount) {

    return """
      {
        "eventType":"FiatDeposited",
        "depositId":"%s",
        "userId":"USER-42",
        "walletAddress":"0x1111111111111111111111111111111111111111",
        "amount":%s,
        "currency":"USD"
      }
      """.formatted(id, amount);
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {

    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();

    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }

      Thread.sleep(200);
    }

    fail("Condition not satisfied within 30 seconds");
  }
  @Test
  void flywayAndDatabaseAreReady() {

    Integer migrationCount = jdbc.queryForObject(
      """
      SELECT COUNT(*)
      FROM flyway_schema_history
      WHERE version IN ('1', '2', '3', '4')
        AND success = true
      """,
      Integer.class
    );

    assertEquals(4, migrationCount);

    Integer inboxTableCount = jdbc.queryForObject(
      """
      SELECT COUNT(*)
      FROM information_schema.tables
      WHERE table_schema = 'public'
        AND table_name = 'kafka_inbox'
      """,
      Integer.class
    );

    assertEquals(1, inboxTableCount);
  }
}

