package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;

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

  private final DepositService depositService;
  private final JsonMapper jsonMapper;
  private final Validator validator;

  public FiatDepositConsumer(
    DepositService depositService,
    JsonMapper jsonMapper,
    Validator validator) {

    this.depositService = depositService;
    this.jsonMapper = jsonMapper;
    this.validator = validator;
  }

  @KafkaListener(
    topics = FiatDepositProducer.TOPIC,
    groupId = "wusd-bridge"
  )
  public void consume(
    ConsumerRecord<String, String> record,
    Acknowledgment acknowledgment) {

    FiatDepositedEvent event = jsonMapper.readValue(
      record.value(),
      FiatDepositedEvent.class
    );

    if (!"FiatDeposited".equals(event.eventType())) {
      throw new IllegalArgumentException(
        "Unexpected event type"
      );
    }

    if (record.key() == null ||
      !record.key().equals(event.depositId())) {

      throw new IllegalArgumentException(
        "Kafka key does not match depositId"
      );
    }

    DepositRequest request = event.toDepositRequest();

    Set<ConstraintViolation<DepositRequest>> violations =
      validator.validate(request);

    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(
        "Invalid FiatDeposited payload"
      );
    }

    var result = depositService.create(request);

    System.out.printf(
      "Consumed deposit=%s partition=%d offset=%d created=%s%n",
      event.depositId(),
      record.partition(),
      record.offset(),
      result.created()
    );

    acknowledgment.acknowledge();
  }
}
