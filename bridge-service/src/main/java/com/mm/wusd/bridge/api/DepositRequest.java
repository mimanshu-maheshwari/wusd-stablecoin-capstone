package com.mm.wusd.bridge.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record DepositRequest(
    @NotBlank String depositId,

    @NotBlank String userId,

    @NotBlank @Pattern(regexp = "0x[a-fA-F0-9]{40}") String walletAddress,

    @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal amount,

    @NotBlank @Pattern(regexp = "USD") String currency) {
}
