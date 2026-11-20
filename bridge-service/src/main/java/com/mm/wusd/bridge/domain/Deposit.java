package com.mm.wusd.bridge.domain;

import java.math.BigInteger;

public record Deposit(String depositId, String userId, String walletAddress,
                      BigInteger amountUnits, String currency, String status) {
}
