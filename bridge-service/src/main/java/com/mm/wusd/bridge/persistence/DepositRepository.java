package com.mm.wusd.bridge.persistence;

import java.util.Optional;

import com.mm.wusd.bridge.domain.Deposit;

public interface DepositRepository {
  Optional<Deposit> findById(String depositId);

  boolean insertIfAbsent(Deposit deposit);
}
