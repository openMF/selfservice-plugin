/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StepUpChallengeTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 0);

  private static StepUpChallenge pending(final int maxAttempts) {
    return StepUpChallenge.issue(
        7L,
        "session-digest",
        StepUpAction.TPT_TRANSFER,
        "payload-digest",
        StepUpChannel.SMS,
        "otp-hash",
        "otp-salt",
        maxAttempts,
        NOW,
        NOW.plusSeconds(300),
        "127.0.0.1");
  }

  @Test
  void issue_startsPendingWithNoAttemptsAndNoGrant() {
    final StepUpChallenge c = pending(5);
    assertEquals(StepUpStatus.PENDING, c.getStatus());
    assertTrue(c.isPending());
    assertEquals(0, c.getAttempts());
    assertEquals(5, c.attemptsRemaining());
    assertEquals(0, c.getResendCount());
    assertNull(c.getGrantHash());
    assertNull(c.getLockedUntil());
    assertEquals(NOW, c.getLastSentAt());
    assertEquals(NOW, c.getCreatedAt());
  }

  @Test
  void isExpired_isInclusiveOfTheExpiryInstant() {
    final StepUpChallenge c = pending(5);
    assertFalse(c.isExpired(NOW));
    assertFalse(c.isExpired(NOW.plusSeconds(299)));
    assertTrue(c.isExpired(NOW.plusSeconds(300)));
    assertTrue(c.isExpired(NOW.plusSeconds(301)));
  }

  @Test
  void failedAttempts_countDown_andLockOnTheLastAllowedAttempt() {
    final StepUpChallenge c = pending(3);
    assertFalse(c.registerFailedAttempt(NOW, 900));
    assertEquals(2, c.attemptsRemaining());
    assertFalse(c.registerFailedAttempt(NOW, 900));
    assertEquals(1, c.attemptsRemaining());
    assertEquals(StepUpStatus.PENDING, c.getStatus());

    assertTrue(c.registerFailedAttempt(NOW, 900), "third failure must lock");
    assertEquals(StepUpStatus.LOCKED, c.getStatus());
    assertEquals(0, c.attemptsRemaining());
    assertEquals(NOW.plusSeconds(900), c.getLockedUntil());
    assertFalse(c.isPending());
  }

  @Test
  void rotateOtp_replacesSecretsAndResetsTtlButNeverTheAttempts() {
    final StepUpChallenge c = pending(5);
    c.registerFailedAttempt(NOW, 900);
    c.registerFailedAttempt(NOW, 900);
    final LocalDateTime later = NOW.plusSeconds(90);

    c.rotateOtp("new-hash", "new-salt", later, later.plusSeconds(300));

    assertEquals("new-hash", c.getOtpHash());
    assertEquals("new-salt", c.getOtpSalt());
    assertEquals(1, c.getResendCount());
    assertEquals(later, c.getLastSentAt());
    assertEquals(later.plusSeconds(300), c.getExpiresAt());
    assertEquals(2, c.getAttempts(), "resend must not reset the attempt counter");
    assertEquals(3, c.attemptsRemaining());
  }

  @Test
  void resendCooldown_isMeasuredFromTheLastSend() {
    final StepUpChallenge c = pending(5);
    assertTrue(c.isResendCoolingDown(NOW.plusSeconds(59), 60));
    assertFalse(c.isResendCoolingDown(NOW.plusSeconds(60), 60));
    assertEquals(NOW.plusSeconds(60), c.resendAvailableAt(60));

    c.rotateOtp("h", "s", NOW.plusSeconds(100), NOW.plusSeconds(400));
    assertTrue(c.isResendCoolingDown(NOW.plusSeconds(150), 60));
    assertFalse(c.isResendCoolingDown(NOW.plusSeconds(160), 60));
  }

  @Test
  void markVerified_storesGrantDigestAndExpiry() {
    final StepUpChallenge c = pending(5);
    c.markVerified("grant-digest", NOW.plusSeconds(120), NOW.plusSeconds(10));
    assertEquals(StepUpStatus.VERIFIED, c.getStatus());
    assertEquals("grant-digest", c.getGrantHash());
    assertEquals(NOW.plusSeconds(120), c.getGrantExpiresAt());
    assertEquals(NOW.plusSeconds(10), c.getVerifiedAt());
    assertNull(c.getConsumedAt());
    assertFalse(c.isPending());
  }

  @Test
  void terminalTransitions() {
    final StepUpChallenge expired = pending(5);
    expired.markExpired();
    assertEquals(StepUpStatus.EXPIRED, expired.getStatus());

    final StepUpChallenge superseded = pending(5);
    superseded.supersede();
    assertEquals(StepUpStatus.SUPERSEDED, superseded.getStatus());
  }

  @Test
  void toString_neverExposesSecrets() {
    final StepUpChallenge c = pending(5);
    c.markVerified("grant-digest", NOW.plusSeconds(120), NOW);
    final String text = c.toString();
    assertNotNull(text);
    assertFalse(text.contains("otp-hash"));
    assertFalse(text.contains("otp-salt"));
    assertFalse(text.contains("grant-digest"));
    assertFalse(text.contains("session-digest"));
    assertFalse(text.contains("payload-digest"));
  }
}
