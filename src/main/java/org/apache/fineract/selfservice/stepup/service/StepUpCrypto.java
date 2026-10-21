/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Cryptographic primitives of the step-up module.
 *
 * <ul>
 *   <li>OTP: digits drawn from a {@link SecureRandom} (leading zeros preserved).
 *   <li>OTP at rest: {@code HMAC-SHA256(secret, "otp:" + salt + ":" + otp)} hex-encoded, with a
 *       random per-challenge salt. The HMAC key comes from {@code
 *       mifos.self.service.stepup.hmac-secret} (environment / property, never the database).
 *   <li>Comparisons are constant time ({@link MessageDigest#isEqual}).
 *   <li>Grant token: 256 random bits, handed to the client once; only its SHA-256 is stored.
 * </ul>
 *
 * <p>A 6-digit OTP has only 10^6 values, so hashing alone cannot stop offline guessing if both the
 * database and the secret leak; that is why the secret lives outside the database and online
 * guessing is capped by the attempt limit.
 */
@Component
public class StepUpCrypto {

  public static final String SECRET_PROPERTY = "mifos.self.service.stepup.hmac-secret";
  static final int MIN_SECRET_LENGTH = 32;

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecureRandom secureRandom;
  private final byte[] secret;

  // Two constructors exist (the second one is for tests): tell Spring which one to use.
  @Autowired
  public StepUpCrypto(final Environment env) {
    this(env.getProperty(SECRET_PROPERTY), new SecureRandom());
  }

  StepUpCrypto(final String secret, final SecureRandom secureRandom) {
    this.secureRandom = secureRandom;
    this.secret =
        secret != null && secret.length() >= MIN_SECRET_LENGTH
            ? secret.getBytes(StandardCharsets.UTF_8)
            : null;
  }

  /** {@code true} when a sufficiently long HMAC secret is configured. */
  public boolean hasUsableSecret() {
    return secret != null;
  }

  /** A numeric OTP of exactly {@code length} digits (leading zeros allowed). */
  public String generateOtp(final int length) {
    final StringBuilder otp = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      otp.append(secureRandom.nextInt(10));
    }
    return otp.toString();
  }

  /** 128-bit random salt, hex encoded (32 chars). */
  public String newSalt() {
    final byte[] bytes = new byte[16];
    secureRandom.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Salted HMAC of the OTP, hex encoded (64 chars). Fails closed without a secret. */
  public String hashOtp(final String otp, final String salt) {
    if (secret == null) {
      throw StepUpException.misconfigured();
    }
    try {
      final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      final byte[] digest =
          mac.doFinal(("otp:" + salt + ":" + otp).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }

  /** Constant-time check of a candidate OTP against the stored salted hash. */
  public boolean otpMatches(final String candidate, final String salt, final String expectedHash) {
    if (candidate == null || salt == null || expectedHash == null) {
      return false;
    }
    final byte[] actual = hashOtp(candidate, salt).getBytes(StandardCharsets.US_ASCII);
    final byte[] expected = expectedHash.getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(actual, expected);
  }

  /** New one-time grant token (URL-safe Base64, no padding, 43 chars). */
  public String newGrantToken() {
    final byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Lower-case hex SHA-256 (64 chars). */
  public static String sha256Hex(final String value) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
