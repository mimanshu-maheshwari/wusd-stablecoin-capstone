package com.mm.wusd.bridge.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
  prefix = "wusd.outbox.worker",
  name = "enabled",
  havingValue = "true"
)
public class MintOutboxScheduler {

  private final MintOutboxWorker worker;

  public MintOutboxScheduler(MintOutboxWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${wusd.outbox.worker.poll-ms:1000}")
  public void poll() {
    worker.runOnce();
  }
}
