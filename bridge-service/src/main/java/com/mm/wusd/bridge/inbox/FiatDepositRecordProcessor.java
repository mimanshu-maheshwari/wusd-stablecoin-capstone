package com.mm.wusd.bridge.inbox;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.outbox.MintOutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiatDepositRecordProcessor {

  private final KafkaInboxRepository inbox;
  private final DepositService depositService;
  private final MintOutboxRepository outbox;

  public FiatDepositRecordProcessor(
    KafkaInboxRepository inbox,
    DepositService depositService,
    MintOutboxRepository outbox
  ) {

    this.inbox = inbox;
    this.depositService = depositService;
    this.outbox = outbox;
  }

  @Transactional
  public ProcessingResult process(ConsumerRecord<String, String> record, DepositRequest request) {

    boolean newInboxRecord = inbox.insertIfAbsent(record, request.depositId());

    if (!newInboxRecord) {
      return new ProcessingResult(false, false);
    }

    DepositService.CreateResult result = depositService.create(request);

    // Same transaction as inbox + deposit.
    // Unique (deposit_id, event_type) prevents
    // duplicate logical mint work.
    outbox.enqueue(request.depositId());

    return new ProcessingResult(true, result.created());
  }

  public record ProcessingResult(boolean newInboxRecord, boolean newDeposit) {
  }
}
