package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.domain.DepositFingerprint;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class InMemoryDepositRepository implements DepositRepository {

  private final ConcurrentMap<String, Deposit> deposits = new ConcurrentHashMap<>();

  @Override
  public Optional<Deposit> findById(String depositId) {
    return Optional.ofNullable(deposits.get(depositId));
  }

  @Override
  public boolean insertIfAbsent(Deposit deposit) {
    return deposits.putIfAbsent(deposit.depositId(), deposit) == null;
  }

  @Override
  public Optional<String> findPayloadHashById(String depositId) {
    return findById(depositId).map(DepositFingerprint::sha256);
  }

  @Override
  public boolean advanceStatus(String depositId, String expectedStatus, String nextStatus) {
    AtomicBoolean updated = new AtomicBoolean(false);
    deposits.computeIfPresent(depositId, (key, existing) -> {
      if (!existing.status().equals(expectedStatus)) {
        return existing;
      }
      updated.set(true);
      return new Deposit(existing.depositId(), existing.userId(), existing.walletAddress(), existing.amountUnits(), existing.currency(), nextStatus);
    });

    return updated.get();
  }
}
