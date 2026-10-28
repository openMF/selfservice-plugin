/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TotpEnrollmentTest {

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 28, 12, 0, 0);

  private TotpEnrollment pending() {
    return TotpEnrollment.pending(7L, "encrypted-secret", 1, T0);
  }

  @Test
  void aNewEnrollment_isPendingAndCleanAndNeverTheSecretInToString() {
    final TotpEnrollment e = pending();
    assertTrue(e.isPending());
    assertFalse(e.isActive());
    assertEquals(0, e.getFailedAttempts());
    assertNull(e.getLastUsedStep());
    assertNull(e.getConfirmedAt());
    assertFalse(e.toString().contains("encrypted-secret"));
  }

  @Test
  void activate_confirmsTheEnrollment() {
    final TotpEnrollment e = pending();
    e.activate(T0.plusMinutes(1));
    assertTrue(e.isActive());
    assertFalse(e.isPending());
    assertEquals(T0.plusMinutes(1), e.getConfirmedAt());
  }

  @Test
  void failures_lockAtTheLimit_andReportRemainingAttempts() {
    final TotpEnrollment e = pending();
    assertFalse(e.registerFailure(T0, 3, 900));
    assertEquals(2, e.failuresRemaining(3));
    assertFalse(e.registerFailure(T0, 3, 900));
    assertTrue(e.registerFailure(T0, 3, 900));
    assertEquals(0, e.failuresRemaining(3));
    assertTrue(e.isLocked(T0.plusSeconds(899)));
    assertFalse(e.isLocked(T0.plusSeconds(900)));
    assertEquals(T0.plusSeconds(900), e.getLockedUntil());
  }

  @Test
  void anExpiredLock_startsAgainWithAllAttempts() {
    final TotpEnrollment e = pending();
    e.registerFailure(T0, 1, 60);
    e.releaseExpiredLock(T0.plusSeconds(30));
    assertTrue(e.isLocked(T0.plusSeconds(30)), "still locked after 30 s");
    e.releaseExpiredLock(T0.plusSeconds(60));
    assertFalse(e.isLocked(T0.plusSeconds(60)));
    assertEquals(0, e.getFailedAttempts());
    assertNull(e.getLockedUntil());
  }

  @Test
  void releaseExpiredLock_doesNotForgetFailuresWithoutALock() {
    final TotpEnrollment e = pending();
    e.registerFailure(T0, 5, 60);
    e.releaseExpiredLock(T0.plusHours(5));
    assertEquals(1, e.getFailedAttempts());
  }

  @Test
  void aGoodCode_usesUpItsStep_andResetsTheFailures() {
    final TotpEnrollment e = pending();
    e.registerFailure(T0, 5, 60);
    e.registerSuccess(500L);
    assertEquals(0, e.getFailedAttempts());
    assertEquals(500L, e.getLastUsedStep());
  }

  @Test
  void replay_refusesTheSameOrAnOlderStep_butNotALaterOne() {
    final TotpEnrollment e = pending();
    assertFalse(e.isReplay(500L), "nothing used yet");
    e.registerSuccess(500L);
    assertTrue(e.isReplay(500L));
    assertTrue(e.isReplay(499L));
    assertFalse(e.isReplay(501L));
  }

  @Test
  void resetFailures_leavesTheUsedStepAlone() {
    final TotpEnrollment e = pending();
    e.registerSuccess(500L);
    e.registerFailure(T0, 1, 60);
    e.resetFailures();
    assertEquals(0, e.getFailedAttempts());
    assertNull(e.getLockedUntil());
    assertEquals(500L, e.getLastUsedStep());
  }
}
