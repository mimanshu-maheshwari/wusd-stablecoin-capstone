package com.mm.wusd.bridge.exception;

public class DepositConflictException extends RuntimeException {

  public DepositConflictException(String depositId) {
    super("Deposit ID already exists with different details: " + depositId);
  }

}
