package com.mm.wusd.bridge.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Repository
public class KafkaInboxRepository {

  private final JdbcTemplate jdbc;

  public KafkaInboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertIfAbsent(ConsumerRecord<String, String> record, String depositId) {

    String sql = """
      INSERT INTO kafka_inbox (
          topic_name,
          partition_number,
          record_offset,
          record_key,
          deposit_id,
          payload_sha256
      )
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (
          topic_name,
          partition_number,
          record_offset
      ) DO NOTHING
      """;
    int affected = jdbc.update(
      sql,
      record.topic(),
      record.partition(),
      record.offset(),
      record.key(),
      depositId,
      sha256(record.value())
    );
    return affected == 1;
  }

  public long countByDepositId(String depositId) {
    Long count = jdbc.queryForObject(
      """
        SELECT COUNT(*)
        FROM kafka_inbox
        WHERE deposit_id = ?
        """,
      Long.class,
      depositId
    );

    return count == null ? 0 : count;
  }

  public long countByRecord(
    String topic,
    int partition,
    long offset) {

    Long count = jdbc.queryForObject(
      """
        SELECT COUNT(*)
        FROM kafka_inbox
        WHERE topic_name = ?
          AND partition_number = ?
          AND record_offset = ?
        """,
      Long.class,
      topic,
      partition,
      offset
    );

    return count == null ? 0 : count;
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest
        .getInstance("SHA-256")
        .digest(
          value.getBytes(StandardCharsets.UTF_8)
        );

      return HexFormat.of().formatHex(digest);

    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(
        "SHA-256 unavailable",
        e
      );
    }
  }
}
