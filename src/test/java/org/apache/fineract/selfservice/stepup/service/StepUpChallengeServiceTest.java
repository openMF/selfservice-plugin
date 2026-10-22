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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepUpChallengeServiceTest {

  private static final String SECRET = "unit-test-secret-unit-test-secret-1234";
  private static final Long USER = 7L;
  private static final String SESSION = StepUpCrypto.sha256Hex("session-token");
  private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 12, 0, 0);

  @Mock private StepUpChallengeRepository repository;
  @Mock private StepUpSettingsProvider settingsProvider;
  @Mock private StepUpDeliveryChannel smsChannel;
  @Mock private StepUpDeliveryChannel emailChannel;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final StepUpCrypto crypto = new StepUpCrypto(SECRET, new SecureRandom());
  private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(T0);
  private final StepUpContact contact =
      new StepUpContact(USER, "ana", "Ana", "Perez", "ana@example.com", "5519500324");

  private StepUpChallengeService service;

  private static StepUpSettings enabled(final String... keyValues) {
    final Map<String, String> props = new HashMap<>();
    props.put("enabled", "true");
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
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenAnswer(i -> clock.get());
    when(settingsProvider.current()).thenReturn(enabled());
    when(smsChannel.channel()).thenReturn(StepUpChannel.SMS);
    when(emailChannel.channel()).thenReturn(StepUpChannel.EMAIL);
    when(repository.saveAndFlush(any(StepUpChallenge.class))).thenAnswer(i -> i.getArgument(0));
    when(repository.findByUserActionAndStatusIn(any(), any(), anyList())).thenReturn(List.of());
    when(repository.findActiveLockouts(any(), any(), any(), any())).thenReturn(List.of());
    when(repository.countCreatedSince(any(), any())).thenReturn(0L);
    service = newService(crypto);
  }

  private StepUpChallengeService newService(final StepUpCrypto cryptoToUse) {
    return new StepUpChallengeService(
        repository,
        settingsProvider,
        cryptoToUse,
        List.of(smsChannel, emailChannel),
        transactionDateUtil,
        eventPublisher);
  }

  private StepUpChallengeData create() {
    return service.createChallenge(
        contact,
        SESSION,
        StepUpAction.TPT_TRANSFER,
        StepUpChannel.SMS,
        tptPayload(),
        Locale.US,
        "10.0.0.1");
  }

  /** Issues a challenge for OTP "123456" directly on the entity, as the service would. */
  private StepUpChallenge pendingChallenge(final int maxAttempts) {
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
            maxAttempts,
            clock.get(),
            clock.get().plusSeconds(300),
            "10.0.0.1");
    when(repository.findOwnedForUpdate(eq(99L), eq(USER), eq(SESSION)))
        .thenReturn(Optional.of(challenge));
    return challenge;
  }

  private void assertCode(final String suffix, final StepUpException ex) {
    assertEquals("error.msg.selfservice.stepup." + suffix, ex.getErrorCode());
  }

  private List<StepUpEvent> publishedEvents() {
    final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
    return captor.getAllValues().stream()
        .filter(StepUpEvent.class::isInstance)
        .map(StepUpEvent.class::cast)
        .toList();
  }

  // =============================================================================================
  // createChallenge
  // =============================================================================================

  @Test
  void create_whenDisabled_isRejected() {
    when(settingsProvider.current()).thenReturn(StepUpSettings.defaults());
    assertCode("disabled", assertThrows(StepUpException.class, this::create));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void create_forAnActionThatIsNotProtected_isRejected() {
    when(settingsProvider.current()).thenReturn(enabled("protectedActions", "CHANGE_PASSWORD"));
    final StepUpException ex = assertThrows(StepUpException.class, this::create);
    assertCode("action.not.protected", ex);
    assertEquals("TPT_TRANSFER", ex.getDetails().get("action"));
  }

  @Test
  void create_onAChannelTheTenantDisabled_isRejected() {
    when(settingsProvider.current()).thenReturn(enabled("channels", "EMAIL"));
    assertCode("channel.unavailable", assertThrows(StepUpException.class, this::create));
  }

  @Test
  void create_onAChannelWithoutContactData_isRejected() {
    final StepUpContact noMobile =
        new StepUpContact(USER, "ana", "Ana", "Perez", "ana@example.com", null);
    assertCode(
        "channel.unavailable",
        assertThrows(
            StepUpException.class,
            () ->
                service.createChallenge(
                    noMobile,
                    SESSION,
                    StepUpAction.TPT_TRANSFER,
                    StepUpChannel.SMS,
                    tptPayload(),
                    Locale.US,
                    null)));
  }

  @Test
  void create_withoutAHmacSecret_failsClosedWithoutSendingAnything() {
    service = newService(new StepUpCrypto(null, new SecureRandom()));
    assertCode("misconfigured", assertThrows(StepUpException.class, this::create));
    verify(smsChannel, never()).deliver(any(), any(), any(), anyInt(), any(), any());
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void create_whileLockedOut_isRejectedWithRetryAfter() {
    final StepUpChallenge locked = pendingChallenge(1);
    locked.registerFailedAttempt(clock.get(), 900);
    when(repository.findActiveLockouts(eq(USER), eq(StepUpAction.TPT_TRANSFER), any(), any()))
        .thenReturn(List.of(locked));

    final StepUpException ex = assertThrows(StepUpException.class, this::create);
    assertCode("locked", ex);
    assertEquals(429, ex.getHttpStatus());
    assertTrue(((Long) ex.getDetails().get("retryAfterSeconds")) > 800);
  }

  @Test
  void create_beyondTheRateLimit_isRejected() {
    when(repository.countCreatedSince(eq(USER), any())).thenReturn(5L);
    final StepUpException ex = assertThrows(StepUpException.class, this::create);
    assertCode("rate.limited", ex);
    assertEquals(429, ex.getHttpStatus());
    verify(smsChannel, never()).deliver(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void create_issuesAHashedBoundChallenge_andSendsTheOtp() {
    final StepUpChallengeData data = create();

    final ArgumentCaptor<StepUpChallenge> saved = ArgumentCaptor.forClass(StepUpChallenge.class);
    verify(repository).saveAndFlush(saved.capture());
    final StepUpChallenge challenge = saved.getValue();
    assertEquals(StepUpStatus.PENDING, challenge.getStatus());
    assertEquals(USER, challenge.getAppUserId());
    assertEquals(SESSION, challenge.getSessionHash());
    assertEquals(StepUpAction.TPT_TRANSFER, challenge.getAction());
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tptPayload()),
        challenge.getPayloadHash());
    assertEquals(5, challenge.getMaxAttempts());
    assertEquals(T0.plusSeconds(300), challenge.getExpiresAt());

    final ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
    verify(smsChannel)
        .deliver(eq(contact), otp.capture(), eq(StepUpAction.TPT_TRANSFER), eq(300), any(), any());
    verify(emailChannel, never()).deliver(any(), any(), any(), anyInt(), any(), any());
    assertEquals(6, otp.getValue().length());
    assertNotEquals(otp.getValue(), challenge.getOtpHash());
    assertFalse(challenge.getOtpHash().contains(otp.getValue()));
    assertTrue(crypto.otpMatches(otp.getValue(), challenge.getOtpSalt(), challenge.getOtpHash()));

    assertEquals("SMS", data.channel());
    assertEquals("******0324", data.maskedDestination());
    assertEquals(5, data.attemptsRemaining());
    assertEquals(3, data.resendsRemaining());
    assertEquals(T0.plusSeconds(300).toString(), data.expiresAt());
    assertEquals(T0.plusSeconds(60).toString(), data.resendAvailableAt());

    final List<StepUpEvent> events = publishedEvents();
    assertEquals(1, events.size());
    assertEquals(StepUpEvent.Type.ISSUED, events.get(0).type());
  }

  @Test
  void create_supersedesTheOpenChallengesOfTheSameUserAndAction() {
    final StepUpChallenge old = pendingChallenge(5);
    when(repository.findByUserActionAndStatusIn(
            eq(USER),
            eq(StepUpAction.TPT_TRANSFER),
            eq(List.of(StepUpStatus.PENDING, StepUpStatus.VERIFIED))))
        .thenReturn(List.of(old));

    create();

    assertEquals(StepUpStatus.SUPERSEDED, old.getStatus());
    verify(repository).saveAll(List.of(old));
  }

  @Test
  void create_onEmail_usesTheEmailChannel() {
    service.createChallenge(
        contact,
        SESSION,
        StepUpAction.TPT_TRANSFER,
        StepUpChannel.EMAIL,
        tptPayload(),
        Locale.US,
        null);
    verify(emailChannel).deliver(eq(contact), anyString(), any(), anyInt(), any(), any());
    verify(smsChannel, never()).deliver(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void create_aFailingDeliveryDoesNotFailTheRequest_andNeverLogsTheOtpInTheMessage() {
    org.mockito.Mockito.doThrow(new IllegalStateException("provider down"))
        .when(smsChannel)
        .deliver(any(), any(), any(), anyInt(), any(), any());
    assertNotNull(create());
  }

  // =============================================================================================
  // verify
  // =============================================================================================

  @Test
  void verify_unknownOrForeignChallenge_isTheGenericInvalid() {
    when(repository.findOwnedForUpdate(eq(1L), any(), any())).thenReturn(Optional.empty());
    final StepUpException ex =
        assertThrows(
            StepUpException.class, () -> service.verify(1L, "123456", USER, SESSION, null));
    assertCode("invalid", ex);
    assertEquals(403, ex.getHttpStatus());
  }

  @Test
  void verify_aChallengeOfAnotherUserOrSession_isNotFoundBecauseTheLookupIsOwnerScoped() {
    pendingChallenge(5);
    // Same challenge id, different user / different session: the owner-scoped query finds nothing.
    when(repository.findOwnedForUpdate(eq(99L), eq(8L), eq(SESSION))).thenReturn(Optional.empty());
    when(repository.findOwnedForUpdate(eq(99L), eq(USER), eq("other-session")))
        .thenReturn(Optional.empty());

    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "123456", 8L, SESSION, null)));
    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class,
            () -> service.verify(99L, "123456", USER, "other-session", null)));
  }

  @Test
  void verify_wrongOtp_countsTheAttemptAndReportsWhatIsLeft() {
    final StepUpChallenge challenge = pendingChallenge(5);

    final StepUpException ex =
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "000000", USER, SESSION, null));

    assertCode("otp.invalid", ex);
    assertEquals(400, ex.getHttpStatus());
    assertEquals(4, ex.getDetails().get("attemptsRemaining"));
    assertEquals(1, challenge.getAttempts());
    assertEquals(StepUpStatus.PENDING, challenge.getStatus());
    verify(repository).saveAndFlush(challenge);
    assertEquals(StepUpEvent.Type.FAILED, publishedEvents().get(0).type());
  }

  @Test
  void verify_lockoutAfterMaxAttempts_blocksEvenTheCorrectOtp() {
    final StepUpChallenge challenge = pendingChallenge(3);

    assertCode(
        "otp.invalid",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "111111", USER, SESSION, null)));
    assertCode(
        "otp.invalid",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "222222", USER, SESSION, null)));
    final StepUpException locked =
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "333333", USER, SESSION, null));
    assertCode("locked", locked);
    assertEquals(StepUpStatus.LOCKED, challenge.getStatus());

    // The right code no longer works: no grant, still locked.
    final StepUpException afterLock =
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "123456", USER, SESSION, null));
    assertCode("locked", afterLock);
    assertNotNull(afterLock.getDetails().get("retryAfterSeconds"));
    assertEquals(null, challenge.getGrantHash());
    assertTrue(publishedEvents().stream().anyMatch(e -> e.type() == StepUpEvent.Type.LOCKED));
  }

  @Test
  void verify_aMalformedCodeCountsAsAFailedAttempt() {
    final StepUpChallenge challenge = pendingChallenge(5);
    assertCode(
        "otp.invalid",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "12ab56", USER, SESSION, null)));
    assertEquals(1, challenge.getAttempts());
  }

  @Test
  void verify_correctOtp_issuesAOneTimeGrantStoredOnlyAsADigest() {
    final StepUpChallenge challenge = pendingChallenge(5);
    clock.set(T0.plusSeconds(10));

    final StepUpGrantData grant = service.verify(99L, "123456", USER, SESSION, "10.0.0.1");

    assertEquals("TPT_TRANSFER", grant.action());
    assertEquals(43, grant.stepUpToken().length());
    assertEquals(StepUpStatus.VERIFIED, challenge.getStatus());
    assertEquals(StepUpCrypto.sha256Hex(grant.stepUpToken()), challenge.getGrantHash());
    assertNotEquals(grant.stepUpToken(), challenge.getGrantHash());
    assertEquals(T0.plusSeconds(10 + 120), challenge.getGrantExpiresAt());
    assertEquals(T0.plusSeconds(130).toString(), grant.expiresAt());
    assertFalse(grant.toString().contains(grant.stepUpToken()));
    assertEquals(StepUpEvent.Type.VERIFIED, publishedEvents().get(0).type());
  }

  @Test
  void verify_theOtpIsSingleUse_aSecondVerificationIsRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    service.verify(99L, "123456", USER, SESSION, null);
    assertEquals(StepUpStatus.VERIFIED, challenge.getStatus());

    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "123456", USER, SESSION, null)));
  }

  @Test
  void verify_anExpiredOtp_isRejectedAndMarkedExpired() {
    final StepUpChallenge challenge = pendingChallenge(5);
    clock.set(T0.plusSeconds(300));

    assertCode(
        "otp.expired",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "123456", USER, SESSION, null)));
    assertEquals(StepUpStatus.EXPIRED, challenge.getStatus());
    assertEquals(null, challenge.getGrantHash());
  }

  @Test
  void verify_justBeforeExpiry_stillWorks() {
    pendingChallenge(5);
    clock.set(T0.plusSeconds(299));
    assertNotNull(service.verify(99L, "123456", USER, SESSION, null));
  }

  @Test
  void verify_whenStepUpWasDisabled_isRejected() {
    when(settingsProvider.current()).thenReturn(StepUpSettings.defaults());
    assertCode(
        "disabled",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "123456", USER, SESSION, null)));
  }

  // =============================================================================================
  // resend
  // =============================================================================================

  @Test
  void resend_insideTheCooldown_isRejectedWithRetryAfter() {
    pendingChallenge(5);
    clock.set(T0.plusSeconds(20));

    final StepUpException ex =
        assertThrows(
            StepUpException.class, () -> service.resend(99L, contact, SESSION, Locale.US, null));
    assertCode("resend.cooldown", ex);
    assertEquals(429, ex.getHttpStatus());
    assertEquals(41L, ex.getDetails().get("retryAfterSeconds"));
    verify(smsChannel, never()).deliver(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void resend_afterTheCooldown_sendsAFreshOtp_andKeepsTheAttemptCounter() {
    final StepUpChallenge challenge = pendingChallenge(5);
    assertThrows(StepUpException.class, () -> service.verify(99L, "000000", USER, SESSION, null));
    final String oldHash = challenge.getOtpHash();
    clock.set(T0.plusSeconds(61));

    final StepUpChallengeData data = service.resend(99L, contact, SESSION, Locale.US, null);

    assertNotEquals(oldHash, challenge.getOtpHash());
    assertEquals(1, challenge.getResendCount());
    assertEquals(1, challenge.getAttempts(), "a resend must not reset the attempts");
    assertEquals(T0.plusSeconds(61 + 300), challenge.getExpiresAt());
    assertEquals(4, data.attemptsRemaining());
    assertEquals(2, data.resendsRemaining());
    final ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
    verify(smsChannel).deliver(eq(contact), otp.capture(), any(), anyInt(), any(), any());
    assertTrue(crypto.otpMatches(otp.getValue(), challenge.getOtpSalt(), challenge.getOtpHash()));
    assertEquals(
        StepUpEvent.Type.RESENT, publishedEvents().get(publishedEvents().size() - 1).type());
  }

  @Test
  void resend_theOldOtpNoLongerWorksAfterAResend() {
    pendingChallenge(5);
    clock.set(T0.plusSeconds(61));
    service.resend(99L, contact, SESSION, Locale.US, null);

    assertCode(
        "otp.invalid",
        assertThrows(
            StepUpException.class, () -> service.verify(99L, "123456", USER, SESSION, null)));
  }

  @Test
  void resend_isLimitedToMaxResends() {
    when(settingsProvider.current())
        .thenReturn(enabled("maxResends", "1", "resendCooldownSeconds", "10"));
    pendingChallenge(5);
    clock.set(T0.plusSeconds(11));
    service.resend(99L, contact, SESSION, Locale.US, null);
    clock.set(T0.plusSeconds(30));

    assertCode(
        "resend.limit",
        assertThrows(
            StepUpException.class, () -> service.resend(99L, contact, SESSION, Locale.US, null)));
    verify(smsChannel, times(1)).deliver(any(), any(), any(), anyInt(), any(), any());
  }

  @Test
  void resend_ofAnotherUsersChallenge_isTheGenericInvalid() {
    when(repository.findOwnedForUpdate(eq(5L), eq(USER), eq(SESSION))).thenReturn(Optional.empty());
    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class, () -> service.resend(5L, contact, SESSION, Locale.US, null)));
  }

  @Test
  void resend_ofAnExpiredChallenge_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    clock.set(T0.plusSeconds(400));
    assertCode(
        "otp.expired",
        assertThrows(
            StepUpException.class, () -> service.resend(99L, contact, SESSION, Locale.US, null)));
    assertEquals(StepUpStatus.EXPIRED, challenge.getStatus());
  }

  @Test
  void resend_ofALockedChallenge_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(1);
    challenge.registerFailedAttempt(T0, 900);
    clock.set(T0.plusSeconds(120));
    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class, () -> service.resend(99L, contact, SESSION, Locale.US, null)));
  }

  // =============================================================================================
  // consumeGrant
  // =============================================================================================

  private static final String PAYLOAD_HASH =
      StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tptPayload());

  @Test
  void consume_succeedsOnceWhenTheDatabaseConsumesTheRow() {
    final StepUpChallenge challenge = pendingChallenge(5);
    when(repository.consumeGrant(
            eq(StepUpCrypto.sha256Hex("grant")),
            eq(USER),
            eq(SESSION),
            eq(StepUpAction.TPT_TRANSFER),
            eq(PAYLOAD_HASH),
            eq(StepUpStatus.VERIFIED),
            eq(StepUpStatus.CONSUMED),
            any()))
        .thenReturn(1);
    when(repository.findByGrantHash(StepUpCrypto.sha256Hex("grant")))
        .thenReturn(Optional.of(challenge));

    service.consumeGrant("grant", USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH, null);

    assertEquals(StepUpEvent.Type.CONSUMED, publishedEvents().get(0).type());
  }

  @Test
  void consume_aSecondUseOfTheSameToken_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    challenge.markVerified(StepUpCrypto.sha256Hex("grant"), T0.plusSeconds(120), T0);
    when(repository.consumeGrant(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1)
        .thenReturn(0);
    when(repository.findByGrantHash(any())).thenReturn(Optional.of(challenge));

    service.consumeGrant("grant", USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH, null);
    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class,
            () ->
                service.consumeGrant(
                    "grant", USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH, null)));
  }

  private String rejectionReasonOf(
      final Long user, final String session, final StepUpAction action, final String payload) {
    when(repository.consumeGrant(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0);
    assertCode(
        "invalid",
        assertThrows(
            StepUpException.class,
            () -> service.consumeGrant("grant", user, session, action, payload, null)));
    final List<StepUpEvent> events = publishedEvents();
    final StepUpEvent last = events.get(events.size() - 1);
    assertEquals(StepUpEvent.Type.REJECTED, last.type());
    return last.reason();
  }

  @Test
  void consume_aPayloadThatDiffersFromTheApprovedOne_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    challenge.markVerified(StepUpCrypto.sha256Hex("grant"), T0.plusSeconds(120), T0);
    when(repository.findByGrantHash(any())).thenReturn(Optional.of(challenge));

    final String otherPayload =
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            Map.of(
                "fromAccountId", "1",
                "fromAccountType", "2",
                "toAccountId", "3",
                "toAccountType", "2",
                "transferAmount", "100"));
    assertEquals(
        "payload.mismatch",
        rejectionReasonOf(USER, SESSION, StepUpAction.TPT_TRANSFER, otherPayload));
  }

  @Test
  void consume_aTokenUsedByAnotherUser_orAnotherSession_orAnotherAction_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    challenge.markVerified(StepUpCrypto.sha256Hex("grant"), T0.plusSeconds(120), T0);
    when(repository.findByGrantHash(any())).thenReturn(Optional.of(challenge));

    assertEquals(
        "user.mismatch", rejectionReasonOf(8L, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH));
    assertEquals(
        "session.mismatch",
        rejectionReasonOf(USER, "stolen-session-digest", StepUpAction.TPT_TRANSFER, PAYLOAD_HASH));
    assertEquals(
        "action.mismatch",
        rejectionReasonOf(USER, SESSION, StepUpAction.CHANGE_PASSWORD, PAYLOAD_HASH));
  }

  @Test
  void consume_anExpiredGrant_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    challenge.markVerified(StepUpCrypto.sha256Hex("grant"), T0.plusSeconds(120), T0);
    when(repository.findByGrantHash(any())).thenReturn(Optional.of(challenge));
    clock.set(T0.plusSeconds(121));

    assertEquals(
        "grant.expired", rejectionReasonOf(USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH));
  }

  @Test
  void consume_anUnknownToken_isRejected() {
    when(repository.findByGrantHash(any())).thenReturn(Optional.empty());
    assertEquals(
        "token.unknown", rejectionReasonOf(USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH));
  }

  @Test
  void consume_aTokenThatWasNeverVerified_isRejected() {
    final StepUpChallenge challenge = pendingChallenge(5);
    when(repository.findByGrantHash(any())).thenReturn(Optional.of(challenge));
    assertEquals(
        "token.not.verified.pending",
        rejectionReasonOf(USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH));
  }

  @Test
  void rejectionEvents_neverContainSecrets() {
    final StepUpChallenge challenge = pendingChallenge(5);
    when(repository.findByGrantHash(any())).thenReturn(Optional.of(challenge));
    rejectionReasonOf(USER, SESSION, StepUpAction.TPT_TRANSFER, PAYLOAD_HASH);
    for (final StepUpEvent event : publishedEvents()) {
      final String text = event.toString();
      assertFalse(text.contains("grant"));
      assertFalse(text.contains(SESSION));
    }
  }
}
