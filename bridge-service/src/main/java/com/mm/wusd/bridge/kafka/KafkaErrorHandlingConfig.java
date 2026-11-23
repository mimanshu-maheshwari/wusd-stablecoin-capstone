package com.mm.wusd.bridge.kafka;

import com.mm.wusd.bridge.exception.DepositConflictException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {

    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

    recoverer.setFailIfSendResultIsError(true);
    recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(15));

    // 1 original delivery + 2 retries.
    FixedBackOff backOff = new FixedBackOff(1000L, 2L);

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));

    handler.setRetryListeners((record, exception, deliveryAttempt) -> {
      System.err.printf("KAFKA FAILURE topic=%s partition=%d offset=%d attempt=%d%n",
        record.topic(), record.partition(), record.offset(), deliveryAttempt);

      exception.printStackTrace();
    });

    handler.addNotRetryableExceptions(IllegalArgumentException.class,
      DepositConflictException.class);

    handler.setCommitRecovered(true);

    return handler;
  }
}
