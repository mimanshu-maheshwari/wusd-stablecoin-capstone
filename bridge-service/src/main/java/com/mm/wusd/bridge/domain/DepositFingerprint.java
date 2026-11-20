
package com.mm.wusd.bridge.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DepositFingerprint {

  private static final String DOMAIN = "wusd-deposit-v1";

  private static final Pattern IDENTIFIER =
    Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private static final Pattern WALLET =
    Pattern.compile("0x[0-9a-f]{40}");

  private DepositFingerprint() {
  }

  public static String sha256(Deposit deposit) {

    validateIdentifier(deposit.depositId());
    validateIdentifier(deposit.userId());

    String wallet = deposit.walletAddress()
      .toLowerCase(Locale.ROOT);

    if (!WALLET.matcher(wallet).matches()) {
      throw new IllegalArgumentException(
        "Invalid wallet address"
      );
    }

    if (deposit.amountUnits() == null
      || deposit.amountUnits().signum() <= 0) {
      throw new IllegalArgumentException(
        "Invalid token amount"
      );
    }

    if (!"USD".equals(deposit.currency())) {
      throw new IllegalArgumentException(
        "Unsupported currency"
      );
    }

    String canonical = String.join(
      "\n",
      DOMAIN,
      deposit.depositId(),
      deposit.userId(),
      wallet,
      deposit.amountUnits().toString(),
      deposit.currency()
    );

    try {
      MessageDigest digest =
        MessageDigest.getInstance("SHA-256");

      byte[] hash = digest.digest(
        canonical.getBytes(StandardCharsets.UTF_8)
      );

      return HexFormat.of().formatHex(hash);

    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(
        "SHA-256 unavailable",
        e
      );
    }
  }

  private static void validateIdentifier(String value) {
    if (value == null
      || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(
        "Invalid deposit or user identifier"
      );
    }
  }
}
