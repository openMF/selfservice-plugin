/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.junit.jupiter.api.Test;

class StepUpCryptoTest {

  private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

  private final StepUpCrypto crypto = new StepUpCrypto(SECRET, new SecureRandom());

  @Test
  void theSecretIsLookedUpOnEveryCall_soATenantSwitchChangesTheKey() {
    final String[] current = {"tenant-a-secret-tenant-a-secret-123456"};
    final StepUpCrypto crypto = new StepUpCrypto(new SecureRandom(), () -> current[0]);

    final String asA = crypto.hashOtp("123456", "salt");
    current[0] = "tenant-b-secret-tenant-b-secret-123456";
    final String asB = crypto.hashOtp("123456", "salt");
    current[0] = null;

    assertNotEquals(asA, asB);
    assertFalse(crypto.hasUsableSecret());
  }

  @Test
  void generateOtp_hasRequestedLengthAndOnlyDigits() {
    for (int length = 4; length <= 8; length++) {
      final String otp = crypto.generateOtp(length);
      assertEquals(length, otp.length());
      assertTrue(otp.chars().allMatch(Character::isDigit), otp);
    }
  }

  @Test
  void generateOtp_preservesLeadingZerosAndCoversTheWholeDigitRange() {
    final Set<Character> firstDigits = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      final String otp = crypto.generateOtp(6);
      assertEquals(6, otp.length());
      firstDigits.add(otp.charAt(0));
    }
    // 500 draws of a uniform digit: seeing '0' first and '9' first is overwhelmingly likely.
    assertTrue(firstDigits.contains('0'), "leading zero must be possible");
    assertTrue(firstDigits.contains('9'));
  }

  @Test
  void hashOtp_isDeterministicForSameSaltAndOtp() {
    final String salt = crypto.newSalt();
    assertEquals(crypto.hashOtp("123456", salt), crypto.hashOtp("123456", salt));
    assertEquals(64, crypto.hashOtp("123456", salt).length());
  }

  @Test
  void hashOtp_differsForDifferentSaltOrOtp() {
    final String saltA = crypto.newSalt();
    final String saltB = crypto.newSalt();
    assertNotEquals(saltA, saltB);
    assertNotEquals(crypto.hashOtp("123456", saltA), crypto.hashOtp("123456", saltB));
    assertNotEquals(crypto.hashOtp("123456", saltA), crypto.hashOtp("123457", saltA));
  }

  @Test
  void hashOtp_dependsOnTheSecret() {
    final StepUpCrypto other =
        new StepUpCrypto("another-secret-another-secret-12345678", new SecureRandom());
    final String salt = "00112233445566778899aabbccddeeff";
    assertNotEquals(crypto.hashOtp("123456", salt), other.hashOtp("123456", salt));
  }

  @Test
  void hashOtp_doesNotContainTheOtp() {
    final String hash = crypto.hashOtp("123456", crypto.newSalt());
    assertFalse(hash.contains("123456"));
  }

  @Test
  void otpMatches_acceptsCorrectAndRejectsWrongOrNullInputs() {
    final String salt = crypto.newSalt();
    final String hash = crypto.hashOtp("042917", salt);
    assertTrue(crypto.otpMatches("042917", salt, hash));
    assertFalse(crypto.otpMatches("042918", salt, hash));
    assertFalse(crypto.otpMatches("42917", salt, hash));
    assertFalse(crypto.otpMatches(null, salt, hash));
    assertFalse(crypto.otpMatches("042917", null, hash));
    assertFalse(crypto.otpMatches("042917", salt, null));
    assertFalse(crypto.otpMatches("042917", salt, "not-a-hash"));
  }

  @Test
  void newGrantToken_isUrlSafeLongAndUnique() {
    final Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      final String token = crypto.newGrantToken();
      assertEquals(43, token.length());
      assertTrue(token.matches("[A-Za-z0-9_-]+"), token);
      assertTrue(tokens.add(token), "token repeated");
    }
  }

  @Test
  void sha256Hex_matchesKnownVector() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        StepUpCrypto.sha256Hex("abc"));
  }

  @Test
  void withoutUsableSecret_hashingFailsClosed() {
    final StepUpCrypto missing = new StepUpCrypto(null, new SecureRandom());
    final StepUpCrypto tooShort = new StepUpCrypto("short", new SecureRandom());
    assertFalse(missing.hasUsableSecret());
    assertFalse(tooShort.hasUsableSecret());
    assertTrue(crypto.hasUsableSecret());

    final StepUpException ex =
        assertThrows(StepUpException.class, () -> missing.hashOtp("123456", "salt"));
    assertEquals("error.msg.selfservice.stepup.misconfigured", ex.getErrorCode());
    assertThrows(StepUpException.class, () -> tooShort.otpMatches("123456", "salt", "hash"));
  }
}
