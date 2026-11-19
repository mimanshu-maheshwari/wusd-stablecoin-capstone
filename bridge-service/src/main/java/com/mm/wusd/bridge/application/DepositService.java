package com.mm.wusd.bridge.application;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.domain.TokenAmount;
import com.mm.wusd.bridge.exception.DepositConflictException;
import com.mm.wusd.bridge.exception.DepositNotFoundException;
import com.mm.wusd.bridge.persistance.DepositRepository;

@Service
public class DepositService {

  private final DepositRepository depositRepository;

  public DepositService(DepositRepository depositRepository) {
    this.depositRepository = depositRepository;
  }

  public CreateResult create(DepositRequest request) throws DepositConflictException {
    Deposit deposit = new Deposit(
        request.depositId(),
        request.userId(),
        request.walletAddress().toLowerCase(Locale.ROOT),
        TokenAmount.toBaseUnits(request.amount()),
        request.currency(),
        "RECIEVED");

    boolean created = depositRepository.insertIfAbsent(deposit);

    if (created) {
      return new CreateResult(deposit, created);
    }

    Deposit existing = depositRepository
        .findById(deposit.depositId())
        .orElseThrow(() -> new IllegalStateException(
            "Deposit disappeared after duplicate detection"));

    if (!existing.equals(deposit)) {
      throw new DepositConflictException(deposit.depositId());
    }

    return new CreateResult(existing, false);
  }

  public Deposit findById(String depositId) {
    return depositRepository.findById(depositId)
        .orElseThrow(() -> new DepositNotFoundException(depositId));
  }

  public record CreateResult(Deposit deposit, Boolean created) {
  }

}
