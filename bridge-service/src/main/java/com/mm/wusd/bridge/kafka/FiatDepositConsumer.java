package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.inbox.FiatDepositRecordProcessor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@Component
public class FiatDepositConsumer {

  private final FiatDepositRecordProcessor processor;
  private final JsonMapper jsonMapper;
  private final Validator validator;

  public FiatDepositConsumer(FiatDepositRecordProcessor processor, JsonMapper jsonMapper,
                             Validator validator) {
    this.processor = processor;
    this.jsonMapper = jsonMapper;
    this.validator = validator;
  }

  @KafkaListener(topics = FiatDepositProducer.TOPIC, groupId = "wusd-bridge")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {

    FiatDepositedEvent event = jsonMapper.readValue(record.value(), FiatDepositedEvent.class);

    if (!"FiatDeposited".equals(event.eventType())) {
      throw new IllegalArgumentException("Unexpected Kafka event type");
    }

    if (record.key() == null || !record.key().equals(event.depositId())) {
      throw new IllegalArgumentException("Kafka key and depositId do not match");
    }

    DepositRequest request = event.toDepositRequest();

    Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

    if (!violations.isEmpty()) {
      throw new IllegalArgumentException("Invalid FiatDeposited payload");
    }

    var result = processor.process(record, request);

    System.out.printf("Kafka processed deposit=%s partition=%d offset=%d inboxNew=%s " +
      "depositNew=%s%n", request.depositId(), record.partition(), record.offset(),
      result.newInboxRecord(), result.newDeposit());

    acknowledgment.acknowledge();
  }
}
