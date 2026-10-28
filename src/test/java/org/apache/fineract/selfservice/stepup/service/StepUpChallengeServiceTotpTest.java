/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.stepup.data.StepUpChallengeData;
import org.apache.fineract.selfservice.stepup.data.StepUpGrantData;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChallenge;
import org.apache.fineract.selfservice.stepup.domain.StepUpChallengeRepository;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.stepup.domain.StepUpStatus;
import org.apache.fineract.selfservice.stepup.event.StepUpEvent;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/** The authenticator-app (TOTP) channel of the step-up challenges. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepUpChallengeServiceTotpTest {

  private static final String SECRET = "unit-test-secret-unit-test-secret-1234";
  private static final Long USER = 7L;
  private static final String SESSION = StepUpCrypto.sha256Hex("session-token");
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 28, 12, 0, 0);

  @Mock private StepUpChallengeRepository repository;
  @Mock private StepUpSettingsProvider settingsProvider;
  @Mock private TotpService totpService;
  @Mock private StepUpDeliveryChannel smsChannel;
  @Mock private StepUpDeliveryChannel emailChannel;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final StepUpCrypto crypto = new StepUpCrypto(SECRET, new SecureRandom());
  private final StepUpContact contact =
      new StepUpContact(USER, "ana", "Ana", "Perez", "ana@example.com", "5519500324");

  private StepUpChallengeService service;

  private static StepUpSettings settings(final String... keyValues) {
    final Map<String, String> props = new HashMap<>();
    props.put("enabled", "true");
    props.put("totpEnabled", "true");
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      props.put(keyValues[i], keyValues[i + 1]);
    }
    return StepUpSettings.fromProperties(props);
  }

  private static Map<String, String> tptPayload() {
    return Map.of(
        "fromAccountId", "1",
        "fromAccountType", "2",
        "toAccountId", "2",
        "toAccountType", "2",
        "transferAmount", "100");
  }

  @BeforeEach
  void setUp() {
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(T0);
    when(settingsProvider.current()).thenReturn(settings());
    when(smsChannel.channel()).thenReturn(StepUpChannel.SMS);
    when(emailChannel.channel()).thenReturn(StepUpChannel.EMAIL);
    when(repository.saveAndFlush(any(StepUpChallenge.class))).thenAnswer(i -> i.getArgument(0));
    when(repository.findByUserActionAndStatusIn(any(), any(), anyList())).thenReturn(List.of());
    when(repository.findActiveLockouts(any(), any(), any(), any())).thenReturn(List.of());
    when(repository.countCreatedSince(any(), any())).thenReturn(0L);
    when(totpService.hasActive(USER)).thenReturn(true);
    when(totpService.lockedSeconds(USER)).thenReturn(Optional.empty());
    service = newService(crypto);
  }

  private StepUpChallengeService newService(final StepUpCrypto cryptoToUse) {
    return new StepUpChallengeService(
        repository,
        settingsProvider,
        cryptoToUse,
        totpService,
        List.of(smsChannel, emailChannel),
        transactionDateUtil,
        eventPublisher);
  }

  private StepUpChallengeData create(final StepUpChannel channel) {
    return service.createChallenge(
        contact, SESSION, StepUpAction.TPT_TRANSFER, channel, tptPayload(), Locale.US, "10.0.0.1");
  }

  private StepUpChallenge pendingTotpChallenge() {
    final StepUpChallenge challenge =
        StepUpChallenge.issue(
            USER,
            SESSION,
            StepUpAction.TPT_TRANSFER,
            StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tptPayload()),
            StepUpChannel.TOTP,
            null,
            null,
            5,
            T0,
            T0.plusSeconds(300),
            "10.0.0.1");
    when(repository.findOwnedForUpdate(eq(99L), eq(USER), eq(SESSION)))
        .thenReturn(Optional.of(challenge));
    return challenge;
  }

  private StepUpChallenge pendingSmsChallenge() {
    final String salt = crypto.newSalt();
    final StepUpChallenge challenge =
        StepUpChallenge.issue(
            USER,
            SESSION,
            StepUpAction.TPT_TRANSFER,
            StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tptPayload()),
            StepUpChannel.SMS,
            crypto.hashOtp("123456", salt),
            salt,
            5,
            T0,
            T0.plusSeconds(300),
            "10.0.0.1");
    when(repository.findOwnedForUpdate(eq(99L), eq(USER), eq(SESSION)))
        .thenReturn(Optional.of(challenge));
    return challenge;
  }

  private void assertCode(final String suffix, final StepUpException ex) {
    assertEquals("error.msg.selfservice.stepup." + suffix, ex.getErrorCode());
  }

  private List<StepUpEvent> events() {
    final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, atLeast(0)).publishEvent(captor.capture());
    return captor.getAllValues().stream()
        .filter(StepUpEvent.class::isInstance)
        .map(StepUpEvent.class::cast)
        .toList();
  }

  // =============================================================================================
  // createChallenge
  // =============================================================================================

  @Test
  void create_totp_opensAChallengeWithNoCode_andSendsNothing() {
    final StepUpChallengeData data = create(StepUpChannel.TOTP);

    final ArgumentCaptor<StepUpChallenge> saved = ArgumentCaptor.forClass(StepUpChallenge.class);
    verify(repository).saveAndFlush(saved.capture());
    final StepUpChallenge challenge = saved.getValue();
    assertEquals(StepUpChannel.TOTP, challenge.getChannel());
    assertEquals(StepUpStatus.PENDING, challenge.getStatus());
    assertNull(challenge.getOtpHash());
    assertNull(challenge.getOtpSalt());
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tptPayload()),
        challenge.getPayloadHash());

    verify(smsChannel, never()).deliver(any(), anyString(), any(), anyInt(), any(), any());
    verify(emailChannel, never()).deliver(any(), anyString(), any(), anyInt(), any(), any());

    assertEquals("TOTP", data.channel());
    assertNull(data.maskedDestination());
    assertEquals(0, data.resendsRemaining());
    assertEquals(5, data.attemptsRemaining());
    assertEquals(
        List.of(StepUpEvent.Type.ISSUED), events().stream().map(StepUpEvent::type).toList());
    assertEquals(StepUpChannel.TOTP, events().get(0).channel());
  }

  @Test
  void create_totp_needsNoHmacSecret_becauseNoCodeIsHashed() {
    when(settingsProvider.current()).thenReturn(settings());
    service = newService(new StepUpCrypto((String) null, new SecureRandom()));
    assertNotNull(create(StepUpChannel.TOTP));
  }

  @Test
  void create_totp_withoutAPairedAuthenticator_isRefused() {
    when(totpService.hasActive(USER)).thenReturn(false);
    assertCode(
        "channel.unavailable",
        assertThrows(StepUpException.class, () -> create(StepUpChannel.TOTP)));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void create_totp_whenTheTenantDidNotSwitchItOn_isRefused() {
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertCode(
        "channel.unavailable",
        assertThrows(StepUpException.class, () -> create(StepUpChannel.TOTP)));
    verify(totpService, never()).hasActive(any());
  }

  @Test
  void create_totp_worksWithTheChannelListEveryTenantAlreadyHas() {
    // the step-up migration seeded "channels = SMS,EMAIL"
    when(settingsProvider.current()).thenReturn(settings("channels", "SMS,EMAIL"));
    assertEquals("TOTP", create(StepUpChannel.TOTP).channel());
  }

  @Test
  void create_totp_whileTheAuthenticatorIsLocked_isRefusedWithRetryAfter() {
    when(totpService.lockedSeconds(USER)).thenReturn(Optional.of(600L));
    final StepUpException ex =
        assertThrows(StepUpException.class, () -> create(StepUpChannel.TOTP));
    assertCode("locked", ex);
    assertEquals(600L, ex.getDetails().get("retryAfterSeconds"));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void create_totp_isStillRateLimited() {
    when(repository.countCreatedSince(any(), any())).thenReturn(5L);
    assertCode(
        "rate.limited", assertThrows(StepUpException.class, () -> create(StepUpChannel.TOTP)));
  }

  @Test
  void create_sms_isRefused_whileAnActiveAuthenticatorIsExclusive() {
    // SIM swap: a stolen session must not be able to fall back to an intercepted SMS
    assertCode(
        "channel.unavailable",
        assertThrows(StepUpException.class, () -> create(StepUpChannel.SMS)));
    assertCode(
        "channel.unavailable",
        assertThrows(StepUpException.class, () -> create(StepUpChannel.EMAIL)));
    verify(smsChannel, never()).deliver(any(), anyString(), any(), anyInt(), any(), any());
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void create_sms_isAllowed_whenTheTenantDoesNotKeepTheAuthenticatorExclusive() {
    when(settingsProvider.current()).thenReturn(settings("totpExclusive", "false"));
    assertEquals("SMS", create(StepUpChannel.SMS).channel());
  }

  @Test
  void create_sms_isAllowed_forAUserWithoutAnAuthenticator() {
    when(totpService.hasActive(USER)).thenReturn(false);
    assertEquals("SMS", create(StepUpChannel.SMS).channel());
  }

  @Test
  void create_sms_isAllowed_whenTheTenantSwitchedTheAuthenticatorOff() {
    when(settingsProvider.current()).thenReturn(settings("totpEnabled", "false"));
    assertEquals("SMS", create(StepUpChannel.SMS).channel());
    verify(totpService, never()).hasActive(any());
  }

  // =============================================================================================
  // verify
  // =============================================================================================

  @Test
  void verify_totp_aCorrectCode_issuesTheOneTimeGrant() {
    final StepUpChallenge challenge = pendingTotpChallenge();
    when(totpService.verify(USER, "123456", null, "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.ACCEPTED, 0, 0));

    final StepUpGrantData grant = service.verify(99L, "123456", null, USER, SESSION, "10.0.0.1");

    assertEquals(StepUpStatus.VERIFIED, challenge.getStatus());
    assertEquals(43, grant.stepUpToken().length());
    assertEquals(StepUpCrypto.sha256Hex(grant.stepUpToken()), challenge.getGrantHash());
    assertTrue(events().stream().anyMatch(e -> e.type() == StepUpEvent.Type.VERIFIED));
  }

  @Test
  void verify_totp_aRecoveryCode_isPassedOnAndIssuesTheGrant() {
    final StepUpChallenge challenge = pendingTotpChallenge();
    when(totpService.verify(USER, null, "ABCDE-FGHJK", "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.RECOVERY_CODE_ACCEPTED, 0, 0));

    service.verify(99L, null, "ABCDE-FGHJK", USER, SESSION, "10.0.0.1");

    assertEquals(StepUpStatus.VERIFIED, challenge.getStatus());
  }

  @Test
  void verify_totp_aWrongCode_countsOnTheChallengeToo() {
    final StepUpChallenge challenge = pendingTotpChallenge();
    when(totpService.verify(USER, "000000", null, "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.WRONG, 4, 0));

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "000000", null, USER, SESSION, "10.0.0.1"));

    assertCode("otp.invalid", ex);
    assertEquals(4, ex.getDetails().get("attemptsRemaining"));
    assertEquals(1, challenge.getAttempts());
    assertEquals(StepUpStatus.PENDING, challenge.getStatus());
    assertNull(challenge.getGrantHash());
    final StepUpEvent failed = events().get(0);
    assertEquals(StepUpEvent.Type.FAILED, failed.type());
    assertEquals("totp.mismatch", failed.reason());
  }

  @Test
  void verify_totp_aWrongCode_reportsTheSmallerOfTheTwoCounts() {
    // earlier wrong codes on other challenges: the authenticator will lock before this challenge
    final StepUpChallenge challenge = pendingTotpChallenge();
    when(totpService.verify(USER, "000000", null, "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.WRONG, 1, 0));

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "000000", null, USER, SESSION, "10.0.0.1"));

    assertEquals(1, ex.getDetails().get("attemptsRemaining"));
    assertEquals(1, challenge.getAttempts());
  }

  @Test
  void verify_totp_whenTheChallengeIsTheNarrowerLimit_itsCountIsReported() {
    pendingTotpChallenge();
    when(totpService.verify(USER, "000000", null, "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.WRONG, 5, 0));
    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "000000", null, USER, SESSION, "10.0.0.1"));
    assertEquals(4, ex.getDetails().get("attemptsRemaining"));
  }

  @Test
  void verify_totp_aWrongRecoveryCode_isReportedAsSuch() {
    pendingTotpChallenge();
    when(totpService.verify(USER, null, "ABCDE-FGHJK", "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.WRONG, 4, 0));

    assertThrows(
        StepUpException.class,
        () -> service.verify(99L, null, "ABCDE-FGHJK", USER, SESSION, "10.0.0.1"));

    assertEquals("recovery.mismatch", events().get(0).reason());
  }

  @Test
  void verify_totp_whenTheAuthenticatorLocks_isRefusedWithoutCountingTwice() {
    final StepUpChallenge challenge = pendingTotpChallenge();
    when(totpService.verify(USER, "000000", null, "10.0.0.1"))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.LOCKED, 0, 900));

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "000000", null, USER, SESSION, "10.0.0.1"));

    assertCode("locked", ex);
    assertEquals(900L, ex.getDetails().get("retryAfterSeconds"));
    assertEquals(0, challenge.getAttempts());
    assertEquals(StepUpStatus.PENDING, challenge.getStatus());
  }

  @Test
  void verify_totp_enoughWrongCodes_lockTheChallengeToo() {
    final StepUpChallenge challenge = pendingTotpChallenge();
    when(totpService.verify(any(), any(), any(), any()))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.WRONG, 1, 0));
    for (int i = 0; i < 4; i++) {
      assertCode(
          "otp.invalid",
          assertThrows(
              StepUpException.class,
              () -> service.verify(99L, "000000", null, USER, SESSION, "10.0.0.1")));
    }
    assertCode(
        "locked",
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "000000", null, USER, SESSION, "10.0.0.1")));
    assertEquals(StepUpStatus.LOCKED, challenge.getStatus());
  }

  @Test
  void verify_totp_doesNotTouchTheSmsHashing() {
    pendingTotpChallenge();
    when(totpService.verify(any(), any(), any(), any()))
        .thenReturn(new TotpService.Verification(TotpService.Outcome.ACCEPTED, 0, 0));
    // a service whose HMAC secret is missing would throw if it tried to compare an OTP hash
    service = newService(new StepUpCrypto((String) null, new SecureRandom()));
    assertNotNull(service.verify(99L, "123456", null, USER, SESSION, "10.0.0.1"));
  }

  @Test
  void verify_aRecoveryCode_isNotAcceptedOnASmsChallenge() {
    final StepUpChallenge challenge = pendingSmsChallenge();

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, null, "ABCDE-FGHJK", USER, SESSION, "10.0.0.1"));

    assertCode("otp.invalid", ex);
    assertEquals(1, challenge.getAttempts());
    verify(totpService, never()).verify(any(), any(), any(), any());
  }

  @Test
  void verify_aRecoveryCodeNextToTheCorrectOtp_isNotAcceptedOnASmsChallenge() {
    final StepUpChallenge challenge = pendingSmsChallenge();
    assertThrows(
        StepUpException.class,
        () -> service.verify(99L, "123456", "ABCDE-FGHJK", USER, SESSION, "10.0.0.1"));
    assertEquals(StepUpStatus.PENDING, challenge.getStatus());
  }

  @Test
  void verify_theOldFiveArgumentCall_stillWorksForSms() {
    final StepUpChallenge challenge = pendingSmsChallenge();
    final StepUpGrantData grant = service.verify(99L, "123456", USER, SESSION, "10.0.0.1");
    assertEquals(StepUpStatus.VERIFIED, challenge.getStatus());
    assertNotNull(grant);
    verify(totpService, never()).verify(any(), any(), any(), any());
  }

  // =============================================================================================
  // resend
  // =============================================================================================

  @Test
  void resend_totp_isNotSupported() {
    pendingTotpChallenge();
    assertCode(
        "resend.unsupported",
        assertThrows(
            StepUpException.class,
            () -> service.resend(99L, contact, SESSION, Locale.US, "10.0.0.1")));
    verify(smsChannel, never()).deliver(any(), anyString(), any(), anyInt(), any(), any());
  }

  @Test
  void resend_sms_isRefused_onceTheUserPairedAnExclusiveAuthenticator() {
    final StepUpChallenge challenge = pendingSmsChallenge();
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(T0.plusMinutes(2));
    assertCode(
        "channel.unavailable",
        assertThrows(
            StepUpException.class,
            () -> service.resend(99L, contact, SESSION, Locale.US, "10.0.0.1")));
    assertEquals(0, challenge.getResendCount());
    verify(smsChannel, never()).deliver(any(), anyString(), any(), anyInt(), any(), any());
  }

  @Test
  void resend_sms_works_whenNoAuthenticatorIsInTheWay() {
    final StepUpChallenge challenge = pendingSmsChallenge();
    when(totpService.hasActive(USER)).thenReturn(false);
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(T0.plusMinutes(2));
    final StepUpChallengeData data = service.resend(99L, contact, SESSION, Locale.US, "10.0.0.1");
    assertEquals(1, challenge.getResendCount());
    assertFalse(data.resendsRemaining() == 0);
  }

  // =============================================================================================
  // the TOTP challenge is not an oracle for anything else
  // =============================================================================================

  @Test
  void verify_totp_aChallengeOfAnotherUser_isTheGenericInvalid() {
    when(repository.findOwnedForUpdate(eq(99L), eq(8L), eq(SESSION))).thenReturn(Optional.empty());
    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "123456", null, 8L, SESSION, "10.0.0.1")));
    verify(totpService, never()).verify(any(), any(), any(), any());
  }
}
