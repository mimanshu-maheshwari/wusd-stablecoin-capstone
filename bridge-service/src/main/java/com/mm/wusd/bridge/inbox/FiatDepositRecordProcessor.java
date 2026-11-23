package com.mm.wusd.bridge.inbox;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiatDepositRecordProcessor {
  private final KafkaInboxRepository inbox;
  private final DepositService depositService;

  public FiatDepositRecordProcessor(KafkaInboxRepository inbox, DepositService depositService) {
    this.inbox = inbox;
    this.depositService = depositService;
  }

  @Transactional
  public ProcessingResult process(
    ConsumerRecord<String, String> record,
    DepositRequest request
  ) {
    boolean newInboxResult = inbox.insertIfAbsent(record, request.depositId());

    if (!newInboxResult) {
      return new ProcessingResult(false, false);
    }

    DepositService.CreateResult result = depositService.create(request);
    return new ProcessingResult(true, result.created());
  }

  public record ProcessingResult(
    boolean newInboxRecord,
    boolean newDeposit
  ) {}

}
