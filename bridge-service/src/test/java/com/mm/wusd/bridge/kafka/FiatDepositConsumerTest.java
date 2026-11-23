package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.inbox.FiatDepositRecordProcessor;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiatDepositConsumerTest {

  private final FiatDepositRecordProcessor processor = mock(FiatDepositRecordProcessor.class);

  private final Acknowledgment acknowledgment = mock(Acknowledgment.class);

  private final JsonMapper mapper = JsonMapper.builder().build();

  private FiatDepositedEvent validEvent() {
    return new FiatDepositedEvent(
      "FiatDeposited",
      "DEP-TEST-1",
      "USER-42",
      "0x1111111111111111111111111111111111111111",
      new BigDecimal("250.50"),
      "USD"
    );
  }

  private ConsumerRecord<String, String> record(String key, FiatDepositedEvent event) {

    return new ConsumerRecord<>(
      "fiat.deposited.v1",
      0,
      5L,
      key,
      mapper.writeValueAsString(event)
    );
  }

  @Test
  void acknowledgesAfterSuccessfulProcessing() {

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {

      FiatDepositConsumer consumer = new FiatDepositConsumer(processor, mapper,
        factory.getValidator());

      when(processor.process(any(ConsumerRecord.class), any(DepositRequest.class)))
        .thenReturn(new FiatDepositRecordProcessor
          .ProcessingResult(true, true));

      consumer.consume(record("DEP-TEST-1", validEvent()), acknowledgment);

      var order = inOrder(processor, acknowledgment);

      order.verify(processor).process(any(ConsumerRecord.class), any(DepositRequest.class));

      order.verify(acknowledgment).acknowledge();
    }
  }

  @Test
  void rejectsMismatchedKafkaKey() {

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {

      FiatDepositConsumer consumer = new FiatDepositConsumer(processor, mapper,
        factory.getValidator());

      assertThrows(IllegalArgumentException.class, () -> consumer.consume(record("WRONG-KEY",
        validEvent()), acknowledgment));

      verifyNoInteractions(processor, acknowledgment);
    }
  }

  @Test
  void doesNotAcknowledgeWhenPersistenceFails() {

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {

      FiatDepositConsumer consumer = new FiatDepositConsumer(processor, mapper,
        factory.getValidator());

      when(processor.process(any(ConsumerRecord.class), any(DepositRequest.class)))
        .thenThrow(new IllegalStateException("Database unavailable"));

      assertThrows(IllegalStateException.class, () -> consumer.consume(record("DEP-TEST-1",
        validEvent()), acknowledgment));

      verify(acknowledgment, never()).acknowledge();
    }
  }
}
