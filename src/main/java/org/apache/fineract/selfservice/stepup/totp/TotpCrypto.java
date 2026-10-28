/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.totp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Protects what the authenticator feature stores. The key is the tenant property {@value
 * org.apache.fineract.selfservice.stepup.service.StepUpSettings#TOTP_KEY_PROPERTY}, so every tenant
 * has its own key and nothing is shared through the environment.
 *
 * <ul>
 *   <li>TOTP secret: it must be read back on every check, so it is encrypted, not hashed.
 *       AES-256-GCM with a random 96-bit nonce; the user id is bound as additional authenticated
 *       data, so a row copied to another user does not decrypt. The AES key is derived from the
 *       tenant property with HMAC-SHA256 (a label per purpose), so the text of the property is
 *       never used as a key directly.
 *   <li>Recovery code: {@code HMAC-SHA256(key, "recovery:" + userId + ":" + code)}. The codes have
 *       50 random bits, so a keyed hash is enough; without the key they cannot be guessed offline.
 * </ul>
 *
 * Every method that needs the key fails closed ({@link StepUpException#misconfigured()}) without a
 * usable one.
 */
@Component
public class TotpCrypto {

  public static final int MIN_KEY_LENGTH = 32;

  /** Stored next to every secret, so the key can be rotated later without guessing. */
  public static final int KEY_VERSION = 1;

  static final int RECOVERY_CODE_LENGTH = 10;

  private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String HMAC = "HmacSHA256";
  private static final String CIPHER = "AES/GCM/NoPadding";

  private final SecureRandom secureRandom;
  private final Supplier<String> keySource;

  @Autowired
  public TotpCrypto(final StepUpSettingsProvider settingsProvider) {
    this(new SecureRandom(), () -> settingsProvider.current().totpEncryptionKey());
  }

  /** The key is looked up on every call, so it always belongs to the tenant of the request. */
  TotpCrypto(final SecureRandom secureRandom, final Supplier<String> keySource) {
    this.secureRandom = secureRandom;
    this.keySource = keySource;
  }

  public SecureRandom random() {
    return secureRandom;
  }

  /** {@code true} when the current tenant has a sufficiently long key. */
  public boolean hasUsableKey() {
    final String key = keySource.get();
    return key != null && key.length() >= MIN_KEY_LENGTH;
  }

  /** Encrypts a secret for one user: Base64 of {@code nonce || ciphertext || tag}. */
  public String encryptSecret(final byte[] secret, final Long userId) {
    final byte[] nonce = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    try {
      final Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(userId));
      final byte[] encrypted = cipher.doFinal(secret);
      final byte[] out = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is not available", e);
    }
  }

  /**
   * Decrypts a secret of this user.
   *
   * @throws IllegalArgumentException when the value was not encrypted with this key for this user
   *     (a changed key, a row of another user, damaged data)
   */
  public byte[] decryptSecret(final String stored, final Long userId) {
    try {
      final byte[] in = Base64.getDecoder().decode(stored);
      if (in.length <= NONCE_BYTES) {
        throw new IllegalArgumentException("The stored secret is too short");
      }
      final Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(
          Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
      cipher.updateAAD(aad(userId));
      return cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("The stored secret cannot be decrypted", e);
    }
  }

  /** A new recovery code: 10 characters without look-alikes (no 0, O, 1, I). */
  public String newRecoveryCode() {
    final StringBuilder code = new StringBuilder(RECOVERY_CODE_LENGTH);
    for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
      code.append(RECOVERY_ALPHABET.charAt(secureRandom.nextInt(RECOVERY_ALPHABET.length())));
    }
    return code.toString();
  }

  /** {@code ABCDE-FGHJK}, easier to read and to copy than ten characters in a row. */
  public static String displayRecoveryCode(final String code) {
    final int half = RECOVERY_CODE_LENGTH / 2;
    return code.substring(0, half) + "-" + code.substring(half);
  }

  /** Upper case, without dashes and spaces; {@code null} when it cannot be a recovery code. */
  public static String normalizeRecoveryCode(final String input) {
    if (input == null) {
      return null;
    }
    final String clean = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    if (clean.length() != RECOVERY_CODE_LENGTH) {
      return null;
    }
    for (int i = 0; i < clean.length(); i++) {
      if (RECOVERY_ALPHABET.indexOf(clean.charAt(i)) < 0) {
        return null;
      }
    }
    return clean;
  }

  /** Keyed hash of a normalised recovery code, hex encoded (64 chars). */
  public String hashRecoveryCode(final String normalizedCode, final Long userId) {
    return HexFormat.of().formatHex(hmac("recovery:" + userId + ":" + normalizedCode));
  }

  // ---------------------------------------------------------------------------------------------

  private static byte[] aad(final Long userId) {
    return ("totp:" + userId).getBytes(StandardCharsets.UTF_8);
  }

  private SecretKeySpec aesKey() {
    return new SecretKeySpec(hmac("totp-secret-encryption-v" + KEY_VERSION), "AES");
  }

  private byte[] hmac(final String message) {
    final String key = keySource.get();
    if (key == null || key.length() < MIN_KEY_LENGTH) {
      throw StepUpException.misconfigured();
    }
    try {
      final Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC));
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }
}
