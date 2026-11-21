package com.mm.wusd.bridge.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

@Service
public class FiatDepositProducer {

  public static final String TOPIC =
    "fiat.deposited.v1";

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper jsonMapper;

  public FiatDepositProducer(
    KafkaTemplate<String, String> kafka,
    JsonMapper jsonMapper) {

    this.kafka = kafka;
    this.jsonMapper = jsonMapper;
  }

  public CompletableFuture<SendResult<String, String>>
  publish(FiatDepositedEvent event) {

    String payload =
      jsonMapper.writeValueAsString(event);

    return kafka.send(
      TOPIC,
      event.depositId(),
      payload
    );
  }
}
