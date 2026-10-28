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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.stepup.data.TotpEnrollmentData;
import org.apache.fineract.selfservice.stepup.data.TotpRecoveryCodesData;
import org.apache.fineract.selfservice.stepup.data.TotpStatusData;
import org.apache.fineract.selfservice.stepup.domain.TotpEnrollment;
import org.apache.fineract.selfservice.stepup.domain.TotpEnrollmentRepository;
import org.apache.fineract.selfservice.stepup.domain.TotpRecoveryCode;
import org.apache.fineract.selfservice.stepup.domain.TotpRecoveryCodeRepository;
import org.apache.fineract.selfservice.stepup.event.TotpEvent;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpSettings;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TotpServiceTest {

  private static final String KEY = "totp-test-key-totp-test-key-123456";
  private static final Long USER = 7L;
  private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 28, 12, 0, 0);
  private static final long STEP = Totp.stepOf(NOW.getEpochSecond());

  @Mock private TotpEnrollmentRepository enrollments;
  @Mock private TotpRecoveryCodeRepository recoveryCodes;
  @Mock private StepUpSettingsProvider settingsProvider;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final TotpCrypto crypto = new TotpCrypto(new SecureRandom(), () -> KEY);
  private final byte[] secret = Totp.newSecret(new SecureRandom());

  private TotpService service;
  private TotpEnrollment stored;

  private static StepUpSettings settings(final String... keyValues) {
    final Map<String, String> props = new HashMap<>();
    props.put("enabled", "true");
    props.put("totpEnabled", "true");
    props.put("totpEncryptionKey", KEY);
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      props.put(keyValues[i], keyValues[i + 1]);
    }
    return StepUpSettings.fromProperties(props);
  }

  @BeforeEach
  void setUp() {
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(T0);
    when(settingsProvider.current()).thenReturn(settings());
    when(enrollments.saveAndFlush(any(TotpEnrollment.class))).thenAnswer(i -> i.getArgument(0));
    when(enrollments.findByAppUserId(USER)).thenAnswer(i -> Optional.ofNullable(stored));
    when(enrollments.findByAppUserIdForUpdate(USER)).thenAnswer(i -> Optional.ofNullable(stored));
    when(recoveryCodes.save(any(TotpRecoveryCode.class))).thenAnswer(i -> i.getArgument(0));
    service =
        new TotpService(
            enrollments,
            recoveryCodes,
            crypto,
            settingsProvider,
            transactionDateUtil,
            eventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private TotpEnrollment storedPending() {
    stored = TotpEnrollment.pending(USER, crypto.encryptSecret(secret, USER), 1, T0);
    return stored;
  }

  private TotpEnrollment storedActive() {
    storedPending().activate(T0);
    return stored;
  }

  private String codeAt(final long step) {
    return Totp.code(secret, step);
  }

  private String wrongCode() {
    final String right = codeAt(STEP);
    return right.equals("000000") ? "000001" : "000000";
  }

  private void assertCode(final String suffix, final StepUpException ex) {
    assertEquals("error.msg.selfservice.stepup." + suffix, ex.getErrorCode());
  }

  private List<TotpEvent> events() {
    final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, atLeast(0)).publishEvent(captor.capture());
    return captor.getAllValues().stream()
        .filter(TotpEvent.class::isInstance)
        .map(TotpEvent.class::cast)
        .toList();
  }

  private static final Runnable NO_CHECK = () -> {};

  // =============================================================================================
  // status / questions
  // =============================================================================================

  @Test
  void status_withNothing_isNone_andAvailableWhenTheTenantSwitchedItOn() {
    final TotpStatusData status = service.status(USER);
    assertEquals("NONE", status.status());
    assertTrue(status.available());
    assertTrue(status.exclusive());
    assertEquals(0, status.recoveryCodesRemaining());
    assertNull(status.confirmedAt());
  }

  @Test
  void status_isNotAvailable_whenTheTenantDidNotSwitchItOn_orHasNoKey() {
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertFalse(service.status(USER).available());

    when(settingsProvider.current()).thenReturn(settings("enabled", "false"));
    assertFalse(service.status(USER).available());

    when(settingsProvider.current()).thenReturn(settings("totpEncryptionKey", "change-me"));
    final TotpService noKey =
        new TotpService(
            enrollments,
            recoveryCodes,
            new TotpCrypto(new SecureRandom(), () -> "change-me"),
            settingsProvider,
            transactionDateUtil,
            eventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertFalse(noKey.status(USER).available());
  }

  @Test
  void status_reportsPendingAndActiveWithTheCodesLeft() {
    storedPending();
    assertEquals("PENDING", service.status(USER).status());
    assertEquals(0, service.status(USER).recoveryCodesRemaining());

    storedActive();
    when(recoveryCodes.countByAppUserIdAndUsedAtIsNull(USER)).thenReturn(8L);
    final TotpStatusData status = service.status(USER);
    assertEquals("ACTIVE", status.status());
    assertEquals(8, status.recoveryCodesRemaining());
    assertEquals(T0.toString(), status.confirmedAt());
  }

  @Test
  void status_exclusiveFollowsTheTenantSetting() {
    when(settingsProvider.current()).thenReturn(settings("totpExclusive", "false"));
    assertFalse(service.status(USER).exclusive());
  }

  @Test
  void hasActive_isTrueOnlyForAConfirmedAuthenticator() {
    assertFalse(service.hasActive(USER));
    storedPending();
    assertFalse(service.hasActive(USER));
    storedActive();
    assertTrue(service.hasActive(USER));
  }

  @Test
  void lockedSeconds_isEmptyUnlessLocked() {
    storedActive();
    assertTrue(service.lockedSeconds(USER).isEmpty());
    stored.registerFailure(T0, 1, 900);
    assertEquals(901L, service.lockedSeconds(USER).orElseThrow());
  }

  // =============================================================================================
  // beginEnrollment
  // =============================================================================================

  @Test
  void begin_whenTheTenantDidNotSwitchItOn_isRefused() {
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertCode(
        "totp.disabled",
        assertThrows(StepUpException.class, () -> service.beginEnrollment(USER, "ana")));
    verify(enrollments, never()).saveAndFlush(any());
  }

  @Test
  void begin_whenStepUpIsOff_isRefused() {
    when(settingsProvider.current()).thenReturn(settings("enabled", "false"));
    assertCode(
        "totp.disabled",
        assertThrows(StepUpException.class, () -> service.beginEnrollment(USER, "ana")));
  }

  @Test
  void begin_withoutAUsableKey_failsClosed() {
    when(settingsProvider.current())
        .thenReturn(settings("totpEncryptionKey", "change-me-before-enabling"));
    final TotpService noKey =
        new TotpService(
            enrollments,
            recoveryCodes,
            new TotpCrypto(new SecureRandom(), () -> "change-me-before-enabling"),
            settingsProvider,
            transactionDateUtil,
            eventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertCode(
        "misconfigured",
        assertThrows(StepUpException.class, () -> noKey.beginEnrollment(USER, "ana")));
    verify(enrollments, never()).saveAndFlush(any());
  }

  @Test
  void begin_storesAnEncryptedPendingSecret_andReturnsItOnce() {
    final TotpEnrollmentData data = service.beginEnrollment(USER, "ana@example.com");

    final ArgumentCaptor<TotpEnrollment> saved = ArgumentCaptor.forClass(TotpEnrollment.class);
    verify(enrollments).saveAndFlush(saved.capture());
    final TotpEnrollment e = saved.getValue();
    assertTrue(e.isPending());
    assertEquals(USER, e.getAppUserId());
    assertEquals(TotpCrypto.KEY_VERSION, e.getKeyVersion());
    // what is stored decrypts to exactly what the user was shown, and is not that text itself
    assertArrayEquals(
        Base32.decode(data.secret()), crypto.decryptSecret(e.getSecretEncrypted(), USER));
    assertFalse(e.getSecretEncrypted().contains(data.secret()));
    assertEquals(32, data.secret().length());
    assertTrue(data.otpauthUri().startsWith("otpauth://totp/Mifos:ana%40example.com?secret="));
    assertTrue(data.otpauthUri().contains("secret=" + data.secret()));
    assertEquals("Mifos", data.issuer());
    assertEquals(6, data.digits());
    assertEquals(30, data.periodSeconds());
  }

  @Test
  void begin_usesTheIssuerOfTheTenant() {
    when(settingsProvider.current()).thenReturn(settings("totpIssuer", "Banco Uno"));
    final TotpEnrollmentData data = service.beginEnrollment(USER, "ana");
    assertEquals("Banco Uno", data.issuer());
    assertTrue(data.otpauthUri().contains("issuer=Banco%20Uno"));
  }

  @Test
  void begin_replacesAPendingSecret() {
    final TotpEnrollment old = storedPending();
    service.beginEnrollment(USER, "ana");
    verify(enrollments).delete(old);
    verify(enrollments).flush();
    verify(enrollments).saveAndFlush(any(TotpEnrollment.class));
  }

  @Test
  void begin_whileAnAuthenticatorIsActive_isRefusedSoItIsNeverReplacedSilently() {
    storedActive();
    assertCode(
        "totp.already.enrolled",
        assertThrows(StepUpException.class, () -> service.beginEnrollment(USER, "ana")));
    verify(enrollments, never()).delete(any());
    verify(enrollments, never()).saveAndFlush(any());
  }

  @Test
  void begin_whenTwoRequestsStartAtTheSameMoment_theLoserIsToldAnAppIsAlreadySetUp() {
    when(enrollments.saveAndFlush(any(TotpEnrollment.class)))
        .thenThrow(new DataIntegrityViolationException("uq_ss_totp_enrollment_user"));
    assertCode(
        "totp.already.enrolled",
        assertThrows(StepUpException.class, () -> service.beginEnrollment(USER, "ana")));
  }

  @Test
  void toStringOfTheEnrollmentData_neverPrintsTheSecret() {
    final TotpEnrollmentData data = service.beginEnrollment(USER, "ana");
    assertFalse(data.toString().contains(data.secret()));
  }

  // =============================================================================================
  // confirmEnrollment
  // =============================================================================================

  @Test
  void confirm_withoutAPendingSecret_isRefused() {
    assertCode(
        "totp.enrollment.missing",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", NO_CHECK)));
  }

  @Test
  void confirm_aWrongCode_isCounted_andDoesNotRunTheStepUpCheck() {
    storedPending();
    final AtomicBoolean checked = new AtomicBoolean();
    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () ->
                service.confirmEnrollment(USER, wrongCode(), "10.0.0.1", () -> checked.set(true)));
    assertCode("otp.invalid", ex);
    assertEquals(4, ex.getDetails().get("attemptsRemaining"));
    assertEquals(1, stored.getFailedAttempts());
    assertTrue(stored.isPending());
    assertFalse(checked.get());
    verify(enrollments).saveAndFlush(stored);
  }

  @Test
  void confirm_aMalformedCode_isCountedToo() {
    storedPending();
    assertCode(
        "otp.invalid",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, "12ab56", "10.0.0.1", NO_CHECK)));
    assertEquals(1, stored.getFailedAttempts());
  }

  @Test
  void confirm_tooManyWrongCodes_lockTheEnrollment() {
    storedPending();
    for (int i = 0; i < 4; i++) {
      assertCode(
          "otp.invalid",
          assertThrows(
              StepUpException.class,
              () -> service.confirmEnrollment(USER, wrongCode(), "10.0.0.1", NO_CHECK)));
    }
    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, wrongCode(), "10.0.0.1", NO_CHECK));
    assertCode("locked", ex);
    assertEquals(900L, ex.getDetails().get("retryAfterSeconds"));
    assertTrue(events().stream().anyMatch(e -> e.type() == TotpEvent.Type.LOCKED));

    // even the right code is refused while locked
    assertCode(
        "locked",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", NO_CHECK)));
    assertTrue(stored.isPending());
  }

  @Test
  void confirm_aValidCode_runsTheStepUpCheck_activates_andReturnsTheRecoveryCodesOnce() {
    storedPending();
    final AtomicBoolean checked = new AtomicBoolean();

    final TotpRecoveryCodesData result =
        service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", () -> checked.set(true));

    assertTrue(checked.get());
    assertTrue(stored.isActive());
    assertEquals(STEP, stored.getLastUsedStep());
    assertEquals(10, result.recoveryCodes().size());
    assertEquals(10, new HashSet<>(result.recoveryCodes()).size());
    result.recoveryCodes().forEach(c -> assertTrue(c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"), c));
    assertEquals(List.of(TotpEvent.Type.ENROLLED), events().stream().map(TotpEvent::type).toList());
    assertEquals("10.0.0.1", events().get(0).ipAddress());
    assertEquals(USER, events().get(0).userId());
  }

  @Test
  void confirm_storesOnlyKeyedHashesOfTheRecoveryCodes() {
    storedPending();
    final TotpRecoveryCodesData result =
        service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", NO_CHECK);

    final ArgumentCaptor<TotpRecoveryCode> saved = ArgumentCaptor.forClass(TotpRecoveryCode.class);
    verify(recoveryCodes, times(10)).save(saved.capture());
    final Set<String> hashes = new HashSet<>();
    saved.getAllValues().forEach(c -> hashes.add(c.getCodeHash()));
    for (final String shown : result.recoveryCodes()) {
      final String normalized = TotpCrypto.normalizeRecoveryCode(shown);
      assertTrue(hashes.contains(crypto.hashRecoveryCode(normalized, USER)));
      assertFalse(hashes.contains(normalized));
    }
    verify(recoveryCodes).deleteByAppUserId(USER);
  }

  @Test
  void confirm_followsTheNumberOfRecoveryCodesOfTheTenant() {
    storedPending();
    when(settingsProvider.current()).thenReturn(settings("recoveryCodeCount", "6"));
    assertEquals(
        6,
        service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", NO_CHECK).recoveryCodes().size());
  }

  @Test
  void confirm_whenTheStepUpCheckRefuses_nothingIsActivated_andNoCodeIsIssued() {
    storedPending();
    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () ->
                service.confirmEnrollment(
                    USER,
                    codeAt(STEP),
                    "10.0.0.1",
                    () -> {
                      throw StepUpException.required(
                          org.apache.fineract.selfservice.stepup.domain.StepUpAction
                              .MANAGE_AUTHENTICATOR);
                    }));
    assertCode("required", ex);
    assertTrue(stored.isPending());
    assertNull(stored.getLastUsedStep());
    verify(recoveryCodes, never()).save(any());
    assertTrue(events().isEmpty());
  }

  @Test
  void confirm_acceptsACodeOfTheNeighbouringStep_butNotOfTheNextButOne() {
    storedPending();
    assertEquals(
        10,
        service
            .confirmEnrollment(USER, codeAt(STEP - 1), "10.0.0.1", NO_CHECK)
            .recoveryCodes()
            .size());

    storedPending();
    assertCode(
        "otp.invalid",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, codeAt(STEP + 2), "10.0.0.1", NO_CHECK)));
  }

  @Test
  void confirm_whenAlreadyActive_isRefused() {
    storedActive();
    assertCode(
        "totp.already.enrolled",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", NO_CHECK)));
  }

  @Test
  void confirm_whenTheTenantSwitchedItOff_isRefused() {
    storedPending();
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertCode(
        "totp.disabled",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, codeAt(STEP), "10.0.0.1", NO_CHECK)));
  }

  @Test
  void confirm_aSecretThatCannotBeDecrypted_isAConfigurationProblemNotAWrongCode() {
    stored =
        TotpEnrollment.pending(USER, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1, T0);
    assertCode(
        "misconfigured",
        assertThrows(
            StepUpException.class,
            () -> service.confirmEnrollment(USER, "123456", "10.0.0.1", NO_CHECK)));
    assertEquals(0, stored.getFailedAttempts());
  }

  // =============================================================================================
  // verify (a step-up challenge)
  // =============================================================================================

  @Test
  void verify_aCorrectCode_isAccepted_andItsStepIsUsedUp() {
    storedActive();
    final TotpService.Verification result = service.verify(USER, codeAt(STEP), null, "10.0.0.1");
    assertEquals(TotpService.Outcome.ACCEPTED, result.outcome());
    assertTrue(result.accepted());
    assertEquals(STEP, stored.getLastUsedStep());
  }

  @Test
  void verify_theSameCodeTwice_isAReplayAndRefused() {
    storedActive();
    assertTrue(service.verify(USER, codeAt(STEP), null, "10.0.0.1").accepted());
    final TotpService.Verification second = service.verify(USER, codeAt(STEP), null, "10.0.0.1");
    assertEquals(TotpService.Outcome.WRONG, second.outcome());
    assertEquals(1, stored.getFailedAttempts());
  }

  @Test
  void verify_anOlderStepThanTheLastUsedOne_isAReplayEvenInsideTheWindow() {
    storedActive();
    assertTrue(service.verify(USER, codeAt(STEP), null, "10.0.0.1").accepted());
    assertFalse(service.verify(USER, codeAt(STEP - 1), null, "10.0.0.1").accepted());
  }

  @Test
  void verify_aLaterStepInsideTheWindow_isAcceptedAfterAnEarlierOne() {
    storedActive();
    assertTrue(service.verify(USER, codeAt(STEP - 1), null, "10.0.0.1").accepted());
    assertTrue(service.verify(USER, codeAt(STEP + 1), null, "10.0.0.1").accepted());
  }

  @Test
  void verify_aWrongCode_isCounted_andReportsWhatIsLeft() {
    storedActive();
    final TotpService.Verification result = service.verify(USER, wrongCode(), null, "10.0.0.1");
    assertEquals(TotpService.Outcome.WRONG, result.outcome());
    assertEquals(4, result.failuresRemaining());
    assertEquals(1, stored.getFailedAttempts());
    assertNull(stored.getLastUsedStep());
  }

  @Test
  void verify_malformedOrMissingCodes_areCountedLikeWrongOnes() {
    storedActive();
    assertEquals(TotpService.Outcome.WRONG, service.verify(USER, "12345", null, "ip").outcome());
    assertEquals(TotpService.Outcome.WRONG, service.verify(USER, "1234567", null, "ip").outcome());
    assertEquals(TotpService.Outcome.WRONG, service.verify(USER, "12ab56", null, "ip").outcome());
    assertEquals(TotpService.Outcome.WRONG, service.verify(USER, null, null, "ip").outcome());
    assertEquals(4, stored.getFailedAttempts());
  }

  @Test
  void verify_tooManyWrongCodes_lockTheAuthenticator_acrossChallenges() {
    storedActive();
    for (int i = 0; i < 4; i++) {
      assertEquals(
          TotpService.Outcome.WRONG, service.verify(USER, wrongCode(), null, "ip").outcome());
    }
    final TotpService.Verification locked = service.verify(USER, wrongCode(), null, "ip");
    assertEquals(TotpService.Outcome.LOCKED, locked.outcome());
    assertEquals(900L, locked.retryAfterSeconds());
    assertEquals(List.of(TotpEvent.Type.LOCKED), events().stream().map(TotpEvent::type).toList());

    // the right code does not help while it is locked, and is not consumed
    final TotpService.Verification refused = service.verify(USER, codeAt(STEP), null, "ip");
    assertEquals(TotpService.Outcome.LOCKED, refused.outcome());
    assertNull(stored.getLastUsedStep());
  }

  @Test
  void verify_afterTheLockRanOut_theUserStartsAgain() {
    storedActive();
    stored.registerFailure(T0.minusSeconds(1000), 1, 900);
    assertTrue(stored.getLockedUntil().isBefore(T0));
    assertTrue(service.verify(USER, codeAt(STEP), null, "ip").accepted());
    assertEquals(0, stored.getFailedAttempts());
  }

  @Test
  void verify_aGoodCodeResetsTheFailureCount() {
    storedActive();
    service.verify(USER, wrongCode(), null, "ip");
    service.verify(USER, wrongCode(), null, "ip");
    assertEquals(2, stored.getFailedAttempts());
    assertTrue(service.verify(USER, codeAt(STEP), null, "ip").accepted());
    assertEquals(0, stored.getFailedAttempts());
  }

  @Test
  void verify_usesTheTotpWindowOfTheTenant() {
    storedActive();
    when(settingsProvider.current()).thenReturn(settings("totpWindowSteps", "0"));
    assertFalse(service.verify(USER, codeAt(STEP - 1), null, "ip").accepted());
    assertTrue(service.verify(USER, codeAt(STEP), null, "ip").accepted());
  }

  @Test
  void verify_usesTheLockoutOfTheTenant() {
    storedActive();
    when(settingsProvider.current())
        .thenReturn(settings("totpMaxFailures", "2", "totpLockoutSeconds", "120"));
    assertEquals(
        TotpService.Outcome.WRONG, service.verify(USER, wrongCode(), null, "ip").outcome());
    final TotpService.Verification locked = service.verify(USER, wrongCode(), null, "ip");
    assertEquals(TotpService.Outcome.LOCKED, locked.outcome());
    assertEquals(120L, locked.retryAfterSeconds());
  }

  @Test
  void verify_withoutAnActiveAuthenticator_isRefused() {
    assertCode(
        "totp.not.enrolled",
        assertThrows(StepUpException.class, () -> service.verify(USER, codeAt(STEP), null, "ip")));
    storedPending();
    assertCode(
        "totp.not.enrolled",
        assertThrows(StepUpException.class, () -> service.verify(USER, codeAt(STEP), null, "ip")));
  }

  @Test
  void verify_whenTheTenantSwitchedItOff_isRefused() {
    storedActive();
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertCode(
        "totp.disabled",
        assertThrows(StepUpException.class, () -> service.verify(USER, codeAt(STEP), null, "ip")));
  }

  @Test
  void verify_aSecretThatCannotBeDecrypted_isNeverAFreePass() {
    stored =
        TotpEnrollment.pending(USER, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1, T0);
    stored.activate(T0);
    assertCode(
        "misconfigured",
        assertThrows(StepUpException.class, () -> service.verify(USER, "123456", null, "ip")));
  }

  @Test
  void verify_theCodeOfAnotherUsersSecret_isRefused() {
    storedActive();
    final byte[] other = Totp.newSecret(new SecureRandom());
    assertFalse(service.verify(USER, Totp.code(other, STEP), null, "ip").accepted());
  }

  // ---- recovery codes ------------------------------------------------------------------------

  @Test
  void verify_aRecoveryCode_worksOnce_andPublishesAnEvent() {
    storedActive();
    when(recoveryCodes.consume(eq(USER), anyString(), any(LocalDateTime.class))).thenReturn(1);

    final TotpService.Verification result = service.verify(USER, null, "abcde-fghjk", "10.0.0.1");

    assertEquals(TotpService.Outcome.RECOVERY_CODE_ACCEPTED, result.outcome());
    assertTrue(result.accepted());
    // the lookup is by keyed hash of the normalised code, never by the code itself
    verify(recoveryCodes).consume(USER, crypto.hashRecoveryCode("ABCDEFGHJK", USER), T0);
    assertEquals(
        List.of(TotpEvent.Type.RECOVERY_CODE_USED),
        events().stream().map(TotpEvent::type).toList());
    assertNull(stored.getLastUsedStep(), "no TOTP step was used");
  }

  @Test
  void verify_aRecoveryCodeThatWasUsedOrDoesNotExist_isCountedAsWrong() {
    storedActive();
    when(recoveryCodes.consume(eq(USER), anyString(), any(LocalDateTime.class))).thenReturn(0);
    final TotpService.Verification result = service.verify(USER, null, "ABCDE-FGHJK", "10.0.0.1");
    assertEquals(TotpService.Outcome.WRONG, result.outcome());
    assertEquals(1, stored.getFailedAttempts());
    assertTrue(events().isEmpty());
  }

  @Test
  void verify_aMalformedRecoveryCode_isCountedAndNeverLooked_up() {
    storedActive();
    final TotpService.Verification result = service.verify(USER, null, "not-a-code", "ip");
    assertEquals(TotpService.Outcome.WRONG, result.outcome());
    verify(recoveryCodes, never()).consume(anyLong(), anyString(), any());
  }

  @Test
  void verify_recoveryCodesCannotBeGuessedBecauseFailuresLockTheAuthenticator() {
    storedActive();
    when(recoveryCodes.consume(eq(USER), anyString(), any(LocalDateTime.class))).thenReturn(0);
    for (int i = 0; i < 4; i++) {
      assertEquals(
          TotpService.Outcome.WRONG, service.verify(USER, null, "ABCDE-FGHJK", "ip").outcome());
    }
    assertEquals(
        TotpService.Outcome.LOCKED, service.verify(USER, null, "ABCDE-FGHJK", "ip").outcome());
    // locked: not even a valid recovery code is tried
    when(recoveryCodes.consume(eq(USER), anyString(), any(LocalDateTime.class))).thenReturn(1);
    assertEquals(
        TotpService.Outcome.LOCKED, service.verify(USER, null, "ABCDE-FGHJK", "ip").outcome());
  }

  @Test
  void verify_aRecoveryCodeResetsTheFailureCount() {
    storedActive();
    service.verify(USER, wrongCode(), null, "ip");
    when(recoveryCodes.consume(eq(USER), anyString(), any(LocalDateTime.class))).thenReturn(1);
    assertTrue(service.verify(USER, null, "ABCDE-FGHJK", "ip").accepted());
    assertEquals(0, stored.getFailedAttempts());
  }

  // =============================================================================================
  // regenerateRecoveryCodes / remove / adminReset
  // =============================================================================================

  @Test
  void regenerate_needsAnActiveAuthenticator() {
    assertCode(
        "totp.not.enrolled",
        assertThrows(
            StepUpException.class, () -> service.regenerateRecoveryCodes(USER, "ip", NO_CHECK)));
    storedPending();
    assertCode(
        "totp.not.enrolled",
        assertThrows(
            StepUpException.class, () -> service.regenerateRecoveryCodes(USER, "ip", NO_CHECK)));
  }

  @Test
  void regenerate_runsTheStepUpCheck_replacesTheCodes_andPublishesAnEvent() {
    storedActive();
    final AtomicBoolean checked = new AtomicBoolean();

    final TotpRecoveryCodesData data =
        service.regenerateRecoveryCodes(USER, "10.0.0.1", () -> checked.set(true));

    assertTrue(checked.get());
    assertEquals(10, data.recoveryCodes().size());
    verify(recoveryCodes).deleteByAppUserId(USER);
    verify(recoveryCodes, times(10)).save(any(TotpRecoveryCode.class));
    assertEquals(
        List.of(TotpEvent.Type.RECOVERY_CODES_REGENERATED),
        events().stream().map(TotpEvent::type).toList());
  }

  @Test
  void regenerate_whenTheStepUpCheckRefuses_theOldCodesStay() {
    storedActive();
    assertThrows(
        StepUpException.class,
        () ->
            service.regenerateRecoveryCodes(
                USER,
                "ip",
                () -> {
                  throw StepUpException.invalid();
                }));
    verify(recoveryCodes, never()).deleteByAppUserId(anyLong());
    verify(recoveryCodes, never()).save(any());
  }

  @Test
  void remove_ofAnActiveAuthenticator_needsTheStepUpCheck_andDeletesEverything() {
    storedActive();
    final AtomicBoolean checked = new AtomicBoolean();

    service.remove(USER, "10.0.0.1", () -> checked.set(true));

    assertTrue(checked.get());
    verify(recoveryCodes).deleteByAppUserId(USER);
    verify(enrollments).deleteByAppUserId(USER);
    assertEquals(List.of(TotpEvent.Type.REMOVED), events().stream().map(TotpEvent::type).toList());
  }

  @Test
  void remove_whenTheStepUpCheckRefuses_nothingIsDeleted() {
    storedActive();
    assertThrows(
        StepUpException.class,
        () ->
            service.remove(
                USER,
                "ip",
                () -> {
                  throw StepUpException.invalid();
                }));
    verify(enrollments, never()).deleteByAppUserId(anyLong());
    verify(recoveryCodes, never()).deleteByAppUserId(anyLong());
  }

  @Test
  void remove_cancellingAPairingThatWasNeverConfirmed_needsNoStepUp_andLeavesNoEvent() {
    storedPending();
    service.remove(
        USER,
        "ip",
        () -> {
          throw new AssertionError("no step-up expected");
        });
    verify(enrollments).deleteByAppUserId(USER);
    assertTrue(events().isEmpty());
  }

  @Test
  void remove_withNothingToRemove_isReportedAsNotEnrolled() {
    assertCode(
        "totp.not.enrolled",
        assertThrows(StepUpException.class, () -> service.remove(USER, "ip", NO_CHECK)));
  }

  @Test
  void remove_whenTheTenantSwitchedItOff_isRefused() {
    storedActive();
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertCode(
        "totp.disabled",
        assertThrows(StepUpException.class, () -> service.remove(USER, "ip", NO_CHECK)));
  }

  @Test
  void adminReset_removesAnActiveAuthenticator_andRecordsItWithoutAnAddress() {
    storedActive();

    assertTrue(service.adminReset(USER));

    verify(recoveryCodes).deleteByAppUserId(USER);
    verify(enrollments).deleteByAppUserId(USER);
    final List<TotpEvent> events = events();
    assertEquals(1, events.size());
    assertEquals(TotpEvent.Type.RESET_BY_STAFF, events.get(0).type());
    assertEquals(USER, events.get(0).userId());
    assertNull(events.get(0).ipAddress(), "the address of the staff member must not be kept");
  }

  @Test
  void adminReset_worksEvenWhenTheTenantSwitchedTheFeatureOff() {
    storedActive();
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertTrue(service.adminReset(USER));
    verify(enrollments).deleteByAppUserId(USER);
  }

  @Test
  void adminReset_ofAPendingPairing_removesItQuietly() {
    storedPending();
    assertTrue(service.adminReset(USER));
    assertTrue(events().isEmpty());
  }

  @Test
  void adminReset_whenThereIsNothing_reportsFalse() {
    assertFalse(service.adminReset(USER));
    verify(enrollments, never()).deleteByAppUserId(anyLong());
    assertTrue(events().isEmpty());
  }

  // =============================================================================================
  // nothing secret leaks
  // =============================================================================================

  @Test
  void events_neverCarryACodeASecretOrAHash() {
    storedPending();
    final String code = codeAt(STEP);
    final TotpRecoveryCodesData codes = service.confirmEnrollment(USER, code, "10.0.0.1", NO_CHECK);
    service.verify(USER, wrongCode(), null, "10.0.0.1");

    final StringBuilder all = new StringBuilder();
    events().forEach(e -> all.append(e));
    final String text = all.toString();
    assertFalse(text.contains(code));
    assertFalse(text.contains(stored.getSecretEncrypted()));
    codes.recoveryCodes().forEach(c -> assertFalse(text.contains(c)));
    assertNotEquals("", text);
  }
}
