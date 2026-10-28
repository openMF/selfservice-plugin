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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class TotpTest {

  /** The key of the RFC 6238 test vectors (SHA-1): ASCII "12345678901234567890". */
  private static final byte[] RFC_SECRET =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  // ---------------------------------------------------------------------------------------------
  // RFC 6238 appendix B (SHA-1). The RFC lists 8 digits; the 6-digit code is the same number
  // modulo 10^6.
  // ---------------------------------------------------------------------------------------------

  @Test
  void code_matchesTheRfc6238TestVectors() {
    assertEquals("287082", Totp.code(RFC_SECRET, Totp.stepOf(59L)));
    assertEquals("081804", Totp.code(RFC_SECRET, Totp.stepOf(1_111_111_109L)));
    assertEquals("050471", Totp.code(RFC_SECRET, Totp.stepOf(1_111_111_111L)));
    assertEquals("005924", Totp.code(RFC_SECRET, Totp.stepOf(1_234_567_890L)));
    assertEquals("279037", Totp.code(RFC_SECRET, Totp.stepOf(2_000_000_000L)));
    assertEquals("353130", Totp.code(RFC_SECRET, Totp.stepOf(20_000_000_000L)));
  }

  @Test
  void code_keepsLeadingZeros() {
    final String code = Totp.code(RFC_SECRET, Totp.stepOf(1_234_567_890L));
    assertEquals(6, code.length());
    assertTrue(code.startsWith("00"));
  }

  @Test
  void stepOf_isA30SecondWindow() {
    assertEquals(0L, Totp.stepOf(0L));
    assertEquals(0L, Totp.stepOf(29L));
    assertEquals(1L, Totp.stepOf(30L));
    assertEquals(1L, Totp.stepOf(59L));
  }

  // ---------------------------------------------------------------------------------------------
  // Window
  // ---------------------------------------------------------------------------------------------

  @Test
  void matchingStep_acceptsTheCurrentStepAndTheNeighboursInsideTheWindow() {
    final long now = 1_000_000L;
    assertEquals(now, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now), now, 1));
    assertEquals(now - 1, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, 1));
    assertEquals(now + 1, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, 1));
  }

  @Test
  void matchingStep_refusesWhatIsOutsideTheWindow() {
    final long now = 1_000_000L;
    assertEquals(-1L, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now - 2), now, 1));
    assertEquals(-1L, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now + 2), now, 1));
  }

  @Test
  void matchingStep_withAWindowOfZeroIsStrict() {
    final long now = 1_000_000L;
    assertEquals(now, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now), now, 0));
    assertEquals(-1L, Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, 0));
  }

  @Test
  void matchingStep_refusesAWrongCode() {
    final long now = 1_000_000L;
    final String right = Totp.code(RFC_SECRET, now);
    final String wrong = right.equals("000000") ? "000001" : "000000";
    assertEquals(-1L, Totp.matchingStep(RFC_SECRET, wrong, now, 1));
  }

  @Test
  void matchingStep_aCodeOfAnotherSecretDoesNotMatch() {
    final long now = 1_000_000L;
    final byte[] other = "another-secret-key!!".getBytes(StandardCharsets.US_ASCII);
    assertEquals(-1L, Totp.matchingStep(RFC_SECRET, Totp.code(other, now), now, 1));
  }

  // ---------------------------------------------------------------------------------------------
  // Secret and URI
  // ---------------------------------------------------------------------------------------------

  @Test
  void newSecret_is160RandomBits_andDifferentEachTime() {
    final SecureRandom random = new SecureRandom();
    final byte[] first = Totp.newSecret(random);
    final byte[] second = Totp.newSecret(random);
    assertEquals(20, first.length);
    assertFalse(java.util.Arrays.equals(first, second));
  }

  @Test
  void toBase32_isThe32CharactersAnAppExpects() {
    final String text = Totp.toBase32(Totp.newSecret(new SecureRandom()));
    assertEquals(32, text.length());
    assertTrue(text.matches("[A-Z2-7]{32}"));
  }

  @Test
  void otpauthUri_carriesTheSettingsEveryAppUnderstands() {
    final String uri = Totp.otpauthUri(RFC_SECRET, "Mifos Bank", "ana@example.com");
    assertEquals(
        "otpauth://totp/Mifos%20Bank:ana%40example.com?secret="
            + Totp.toBase32(RFC_SECRET)
            + "&issuer=Mifos%20Bank&algorithm=SHA1&digits=6&period=30",
        uri);
  }

  @Test
  void otpauthUri_aColonInTheIssuerOrAccountCannotBreakTheLabel() {
    final String uri = Totp.otpauthUri(RFC_SECRET, "Bad:Issuer", "a:b");
    // exactly one colon in the label: the separator between issuer and account
    final String label = uri.substring("otpauth://totp/".length(), uri.indexOf('?'));
    assertEquals(1, label.chars().filter(c -> c == ':').count());
    assertEquals("BadIssuer:ab", label);
  }

  @Test
  void otpauthUri_percentEncodesWhatCouldInjectAParameter() {
    final String uri = Totp.otpauthUri(RFC_SECRET, "Bank&secret=EVIL", "x");
    assertFalse(uri.contains("&secret=EVIL"));
    // the only "secret=" is the real one
    assertEquals(1, uri.split("secret=", -1).length - 1);
  }

  // ---------------------------------------------------------------------------------------------
  // Base32 (RFC 4648 section 10)
  // ---------------------------------------------------------------------------------------------

  @Test
  void base32_encodesTheRfc4648Vectors() {
    assertEquals("", Base32.encode(new byte[0]));
    assertEquals("MY", Base32.encode("f".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("MZXQ", Base32.encode("fo".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("MZXW6", Base32.encode("foo".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("MZXW6YQ", Base32.encode("foob".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("MZXW6YTB", Base32.encode("fooba".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  void base32_decodesWhatItEncodes_ignoringCaseSpacesAndDashes() {
    final byte[] secret = Totp.newSecret(new SecureRandom());
    final String text = Totp.toBase32(secret);
    assertArrayEquals(secret, Base32.decode(text));
    assertArrayEquals(secret, Base32.decode(text.toLowerCase(java.util.Locale.ROOT)));
    assertArrayEquals(
        secret,
        Base32.decode(
            text.substring(0, 8) + " " + text.substring(8, 16) + "-" + text.substring(16)));
  }

  @Test
  void base32_refusesACharacterOutsideTheAlphabet() {
    assertThrows(IllegalArgumentException.class, () -> Base32.decode("AB1D"));
  }
}
