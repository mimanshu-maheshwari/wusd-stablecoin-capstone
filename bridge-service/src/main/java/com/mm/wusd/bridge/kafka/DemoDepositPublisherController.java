package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.api.DepositRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoDepositPublisherController {

  private final FiatDepositProducer producer;

  public DemoDepositPublisherController(
    FiatDepositProducer producer) {

    this.producer = producer;
  }

  public record PublishResponse(
    String depositId,
    String status,
    Integer partition,
    Long offset
  ) {}

  @PostMapping("/fiat-deposits")
  public CompletableFuture<ResponseEntity<PublishResponse>>
  publish(@Valid @RequestBody DepositRequest request) {

    FiatDepositedEvent event =
      FiatDepositedEvent.from(request);

    return producer.publish(event)
      .handle((result, failure) -> {

        if (failure != null) {
          return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new PublishResponse(
              request.depositId(),
              "KAFKA_PUBLICATION_FAILED",
              null,
              null
            ));
        }

        return ResponseEntity
          .status(HttpStatus.ACCEPTED)
          .body(new PublishResponse(
            request.depositId(),
            "KAFKA_ACCEPTED",
            result.getRecordMetadata().partition(),
            result.getRecordMetadata().offset()
          ));
      });
  }
}
