package com.mm.wusd.bridge.persistance;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

import com.mm.wusd.bridge.domain.Deposit;

@Repository
public class InMemoryDepositRepository implements DepositRepository {

  private ConcurrentMap<String, Deposit> deposits = new ConcurrentHashMap<>();

  @Override
  public Optional<Deposit> findById(String depositId) {
    return Optional.ofNullable(deposits.get(depositId));
  }

  @Override
  public boolean insertIfAbsent(Deposit deposit) {
    return deposits.putIfAbsent(deposit.depositId(), deposit) == null;
  }

}
