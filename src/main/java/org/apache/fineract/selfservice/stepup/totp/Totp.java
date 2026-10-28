/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 (HOTP of RFC 4226 over 30-second steps).
 *
 * <p>The algorithm (SHA-1), the length (6 digits) and the period (30 s) are fixed on purpose:
 * Google Authenticator and several other apps ignore any other value in the enrolment URI, so a
 * different setting would produce codes the user's app can never match.
 */
public final class Totp {

  public static final int DIGITS = 6;
  public static final int PERIOD_SECONDS = 30;

  /** 160 bits, the size RFC 4226 recommends. */
  public static final int SECRET_BYTES = 20;

  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final int MODULO = 1_000_000;

  private Totp() {}

  /** A new random secret. */
  public static byte[] newSecret(final SecureRandom random) {
    final byte[] secret = new byte[SECRET_BYTES];
    random.nextBytes(secret);
    return secret;
  }

  /** The 30-second step a Unix time (seconds) falls in. */
  public static long stepOf(final long epochSeconds) {
    return Math.floorDiv(epochSeconds, PERIOD_SECONDS);
  }

  /** The 6-digit code of one step (leading zeros kept). */
  public static String code(final byte[] secret, final long step) {
    try {
      final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      final byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
      final int offset = hash[hash.length - 1] & 0x0F;
      final int binary =
          ((hash[offset] & 0x7F) << 24)
              | ((hash[offset + 1] & 0xFF) << 16)
              | ((hash[offset + 2] & 0xFF) << 8)
              | (hash[offset + 3] & 0xFF);
      return String.format("%0" + DIGITS + "d", binary % MODULO);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA1 is not available", e);
    }
  }

  /**
   * The step whose code equals {@code candidate}, looking {@code windowSteps} steps back and
   * forward from {@code currentStep} (clock drift), or {@code -1}. Every step of the window is
   * always compared, in constant time, so the answer does not leak where the match was.
   */
  public static long matchingStep(
      final byte[] secret, final String candidate, final long currentStep, final int windowSteps) {
    final byte[] given = candidate.getBytes(StandardCharsets.US_ASCII);
    long match = -1L;
    for (long step = currentStep - windowSteps; step <= currentStep + windowSteps; step++) {
      final byte[] expected = code(secret, step).getBytes(StandardCharsets.US_ASCII);
      if (MessageDigest.isEqual(expected, given)) {
        match = step;
      }
    }
    return match;
  }

  /** The Base32 text of a secret, as typed into an authenticator app. */
  public static String toBase32(final byte[] secret) {
    return Base32.encode(secret);
  }

  /**
   * The {@code otpauth://} URI an authenticator app reads from a QR code. The issuer is shown in
   * the app next to the account; label and issuer are percent-encoded and a colon in either would
   * break the format, so it is removed.
   */
  public static String otpauthUri(
      final byte[] secret, final String issuer, final String accountName) {
    final String cleanIssuer = issuer.replace(":", "");
    final String label = encode(cleanIssuer) + ":" + encode(accountName.replace(":", ""));
    return "otpauth://totp/"
        + label
        + "?secret="
        + toBase32(secret)
        + "&issuer="
        + encode(cleanIssuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + PERIOD_SECONDS;
  }

  private static String encode(final String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
