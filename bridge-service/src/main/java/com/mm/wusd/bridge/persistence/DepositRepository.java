package com.mm.wusd.bridge.persistence;

import com.mm.wusd.bridge.domain.Deposit;

import java.util.Optional;

public interface DepositRepository {

  Optional<Deposit> findById(String depositId);

  boolean insertIfAbsent(Deposit deposit);

  Optional<String> findPayloadHashById(String depositId);

  boolean advanceStatus(String depositId, String expectedStatus, String nextStatus);
}
