/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.totp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.junit.jupiter.api.Test;

class TotpCryptoTest {

  private static final String KEY = "totp-test-key-totp-test-key-123456";
  private static final Long USER = 7L;

  private final TotpCrypto crypto = new TotpCrypto(new SecureRandom(), () -> KEY);

  private static TotpCrypto withKey(final String key) {
    return new TotpCrypto(new SecureRandom(), () -> key);
  }

  // ---------------------------------------------------------------------------------------------
  // Key
  // ---------------------------------------------------------------------------------------------

  @Test
  void hasUsableKey_needsAtLeast32Characters() {
    assertTrue(withKey("x".repeat(32)).hasUsableKey());
    assertFalse(withKey("x".repeat(31)).hasUsableKey());
    assertFalse(withKey("").hasUsableKey());
    assertFalse(withKey(null).hasUsableKey());
    // the placeholder seeded by Liquibase must never count as a key
    assertFalse(withKey("change-me-before-enabling").hasUsableKey());
  }

  @Test
  void withoutAUsableKey_everyOperationFailsClosed() {
    final TotpCrypto none = withKey("too-short");
    final StepUpException encrypt =
        assertThrows(StepUpException.class, () -> none.encryptSecret(new byte[20], USER));
    assertEquals("error.msg.selfservice.stepup.misconfigured", encrypt.getErrorCode());
    assertThrows(StepUpException.class, () -> none.decryptSecret("AAAAAAAAAAAAAAAAAAAAAAAA", USER));
    assertThrows(StepUpException.class, () -> none.hashRecoveryCode("ABCDEFGHJK", USER));
  }

  @Test
  void theKeyIsReadOnEveryCall_soEachTenantUsesItsOwn() {
    final AtomicReference<String> tenantKey = new AtomicReference<>(KEY);
    final TotpCrypto shared = new TotpCrypto(new SecureRandom(), tenantKey::get);
    final byte[] secret = Totp.newSecret(new SecureRandom());
    final String stored = shared.encryptSecret(secret, USER);

    tenantKey.set("another-tenant-key-another-tenant-key");
    assertThrows(IllegalArgumentException.class, () -> shared.decryptSecret(stored, USER));

    tenantKey.set(KEY);
    assertArrayEquals(secret, shared.decryptSecret(stored, USER));
  }

  // ---------------------------------------------------------------------------------------------
  // Secret encryption
  // ---------------------------------------------------------------------------------------------

  @Test
  void encryptedSecret_roundTrips() {
    final byte[] secret = Totp.newSecret(new SecureRandom());
    assertArrayEquals(secret, crypto.decryptSecret(crypto.encryptSecret(secret, USER), USER));
  }

  @Test
  void encryptedSecret_isNotTheSecret_andNeverTheSameTwice() {
    final byte[] secret = Totp.newSecret(new SecureRandom());
    final String first = crypto.encryptSecret(secret, USER);
    final String second = crypto.encryptSecret(secret, USER);
    assertNotEquals(first, second);
    assertFalse(first.contains(Totp.toBase32(secret)));
    // 12-byte nonce + 20-byte secret + 16-byte tag
    assertEquals(48, Base64.getDecoder().decode(first).length);
    assertTrue(first.length() <= 255, "it must fit the secret_enc column");
  }

  @Test
  void aSecretCopiedToAnotherUser_doesNotDecrypt() {
    final String stored = crypto.encryptSecret(Totp.newSecret(new SecureRandom()), USER);
    assertThrows(IllegalArgumentException.class, () -> crypto.decryptSecret(stored, 8L));
  }

  @Test
  void aSecretUnderAnotherKey_doesNotDecrypt() {
    final String stored = crypto.encryptSecret(Totp.newSecret(new SecureRandom()), USER);
    assertThrows(
        IllegalArgumentException.class,
        () -> withKey("a-different-key-a-different-key-12345").decryptSecret(stored, USER));
  }

  @Test
  void aTamperedSecret_doesNotDecrypt() {
    final byte[] raw =
        Base64.getDecoder().decode(crypto.encryptSecret(Totp.newSecret(new SecureRandom()), USER));
    raw[raw.length - 1] ^= 0x01;
    final String tampered = Base64.getEncoder().encodeToString(raw);
    assertThrows(IllegalArgumentException.class, () -> crypto.decryptSecret(tampered, USER));
  }

  @Test
  void aTooShortOrBrokenValue_isRefusedNotCrashed() {
    assertThrows(IllegalArgumentException.class, () -> crypto.decryptSecret("AAAA", USER));
    assertThrows(IllegalArgumentException.class, () -> crypto.decryptSecret("not base64 !!", USER));
  }

  // ---------------------------------------------------------------------------------------------
  // Recovery codes
  // ---------------------------------------------------------------------------------------------

  @Test
  void newRecoveryCode_has10CharactersWithoutLookAlikes() {
    for (int i = 0; i < 200; i++) {
      final String code = crypto.newRecoveryCode();
      assertEquals(10, code.length());
      assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}"), code);
    }
  }

  @Test
  void newRecoveryCode_isRandom() {
    final Set<String> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      seen.add(crypto.newRecoveryCode());
    }
    assertEquals(500, seen.size());
  }

  @Test
  void displayAndNormalize_roundTrip() {
    assertEquals("ABCDE-FGHJK", TotpCrypto.displayRecoveryCode("ABCDEFGHJK"));
    assertEquals("ABCDEFGHJK", TotpCrypto.normalizeRecoveryCode("ABCDE-FGHJK"));
    assertEquals("ABCDEFGHJK", TotpCrypto.normalizeRecoveryCode(" abcde fghjk "));
    assertEquals("ABCDEFGHJK", TotpCrypto.normalizeRecoveryCode("abcdefghjk"));
  }

  @Test
  void normalize_refusesWhatCannotBeARecoveryCode() {
    assertNull(TotpCrypto.normalizeRecoveryCode(null));
    assertNull(TotpCrypto.normalizeRecoveryCode(""));
    assertNull(TotpCrypto.normalizeRecoveryCode("ABCDE-FGHJ"));
    assertNull(TotpCrypto.normalizeRecoveryCode("ABCDE-FGHJKL"));
    // 0, O, 1 and I are not in the alphabet
    assertNull(TotpCrypto.normalizeRecoveryCode("ABCDE-FGHJ0"));
    assertNull(TotpCrypto.normalizeRecoveryCode("ABCDE-FGHJI"));
    assertNull(TotpCrypto.normalizeRecoveryCode("12345-67890"));
    assertNull(TotpCrypto.normalizeRecoveryCode("ABCDE-FGHJ;"));
  }

  @Test
  void recoveryHash_isKeyedAndBoundToTheUser() {
    final String hash = crypto.hashRecoveryCode("ABCDEFGHJK", USER);
    assertEquals(64, hash.length());
    assertTrue(hash.matches("[0-9a-f]{64}"));
    assertEquals(hash, crypto.hashRecoveryCode("ABCDEFGHJK", USER));
    assertNotEquals(hash, crypto.hashRecoveryCode("ABCDEFGHJK", 8L));
    assertNotEquals(hash, crypto.hashRecoveryCode("ABCDEFGHJL", USER));
    assertNotEquals(
        hash,
        withKey("a-different-key-a-different-key-12345").hashRecoveryCode("ABCDEFGHJK", USER));
    assertFalse(hash.contains("ABCDEFGHJK"));
  }
}
