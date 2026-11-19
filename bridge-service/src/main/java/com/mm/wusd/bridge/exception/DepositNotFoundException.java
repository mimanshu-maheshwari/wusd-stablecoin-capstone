package com.mm.wusd.bridge.exception;

public class DepositNotFoundException extends RuntimeException {

    public DepositNotFoundException(String depositId) {
        super("Deposit not found: " + depositId);
    }
}
