package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.domain.Deposit;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FiatDepositConsumerTest {

  private final DepositService service =
    mock(DepositService.class);

  private final Acknowledgment acknowledgment =
    mock(Acknowledgment.class);

  private final JsonMapper mapper =
    JsonMapper.builder().build();

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

  private ConsumerRecord<String, String> record(
    String key,
    FiatDepositedEvent event) {

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

    try (ValidatorFactory factory =
           Validation.buildDefaultValidatorFactory()) {

      Validator validator = factory.getValidator();

      FiatDepositConsumer consumer =
        new FiatDepositConsumer(
          service,
          mapper,
          validator
        );

      Deposit savedDeposit = new Deposit(
        "DEP-TEST-1",
        "USER-42",
        "0x1111111111111111111111111111111111111111",
        new BigInteger("250500000"),
        "USD",
        "RECEIVED"
      );

      when(service.create(any(DepositRequest.class)))
        .thenReturn(
          new DepositService.CreateResult(
            savedDeposit,
            true
          )
        );

      consumer.consume(
        record("DEP-TEST-1", validEvent()),
        acknowledgment
      );

      InOrder order = inOrder(service, acknowledgment);

      order.verify(service)
        .create(any(DepositRequest.class));

      order.verify(acknowledgment)
        .acknowledge();
    }
  }


  @Test
  void rejectsMismatchedKafkaKey() {

    try (ValidatorFactory factory =
           Validation.buildDefaultValidatorFactory()) {

      FiatDepositConsumer consumer =
        new FiatDepositConsumer(
          service,
          mapper,
          factory.getValidator()
        );

      assertThrows(
        IllegalArgumentException.class,
        () -> consumer.consume(
          record("WRONG-KEY", validEvent()),
          acknowledgment
        )
      );

      verifyNoInteractions(
        service,
        acknowledgment
      );
    }
  }

  @Test
  void doesNotAcknowledgeWhenPersistenceFails() {

    try (ValidatorFactory factory =
           Validation.buildDefaultValidatorFactory()) {

      FiatDepositConsumer consumer =
        new FiatDepositConsumer(
          service,
          mapper,
          factory.getValidator()
        );

      when(service.create(any(DepositRequest.class)))
        .thenThrow(
          new IllegalStateException(
            "Database unavailable"
          )
        );

      assertThrows(
        IllegalStateException.class,
        () -> consumer.consume(
          record("DEP-TEST-1", validEvent()),
          acknowledgment
        )
      );

      verify(acknowledgment, never())
        .acknowledge();
    }
  }
}
