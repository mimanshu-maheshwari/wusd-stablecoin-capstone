package com.mm.wusd.bridge.application;

import com.mm.wusd.bridge.api.DepositRequest;
import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.domain.DepositFingerprint;
import com.mm.wusd.bridge.domain.TokenAmount;
import com.mm.wusd.bridge.exception.DepositConflictException;
import com.mm.wusd.bridge.exception.DepositNotFoundException;
import com.mm.wusd.bridge.persistence.DepositRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class DepositService {

  private final DepositRepository depositRepository;

  public DepositService(DepositRepository depositRepository) {
    this.depositRepository = depositRepository;
  }

  public CreateResult create(DepositRequest request) {

    Deposit deposit = new Deposit(request.depositId(), request.userId(), request.walletAddress().toLowerCase(Locale.ROOT), TokenAmount.toBaseUnits(request.amount()), request.currency(), "RECEIVED");
    String incomingHash = DepositFingerprint.sha256(deposit);

    boolean created = depositRepository.insertIfAbsent(deposit);
    if (created) {
      return new CreateResult(deposit, true);
    }

    String storedHash = depositRepository.findPayloadHashById(deposit.depositId()).orElseThrow(() -> new IllegalStateException("Deposit disappeared after duplicate detection"));
    if (!storedHash.equals(incomingHash)) {
      throw new DepositConflictException(deposit.depositId());
    }
    Deposit existing = depositRepository.findById(deposit.depositId()).orElseThrow(() -> new IllegalStateException("Deposit disappeared after hash verification"));

    return new CreateResult(existing, false);
  }

  public Deposit findById(String depositId) {
    return depositRepository.findById(depositId).orElseThrow(() -> new DepositNotFoundException(depositId));
  }

  public boolean advanceStatus(String depositId, String expectedStatus, String nextStatus) {
    if (!isAllowedTransition(expectedStatus, nextStatus)) {
      throw new IllegalArgumentException("Invalid transition: " + expectedStatus + " -> " + nextStatus);
    }

    return depositRepository.advanceStatus(depositId, expectedStatus, nextStatus);
  }

  private boolean isAllowedTransition(String from, String to) {

    return switch (from) {
      case "RECEIVED" -> "VALIDATED".equals(to);
      case "VALIDATED" -> "MINTING".equals(to);
      case "MINTING" -> "MINTED".equals(to);
      default -> false;
    };
  }

  public record CreateResult(Deposit deposit, boolean created) {
  }
}
