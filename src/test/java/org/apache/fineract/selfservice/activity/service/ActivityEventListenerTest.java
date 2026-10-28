/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.selfservice.activity.domain.ActivityOutcome;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.stepup.event.StepUpEvent;
import org.apache.fineract.selfservice.stepup.event.TotpEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActivityEventListenerTest {

  @Mock private ActivityLogService activityLogService;

  private ActivityEventListener listener;

  @BeforeEach
  void setUp() {
    listener = new ActivityEventListener(activityLogService);
  }

  private static SelfServiceNotificationEvent event(
      final SelfServiceNotificationEvent.Type type,
      final Long userId,
      final Map<String, Object> context) {
    return SelfServiceNotificationEvent.withTenantContext(
        "test",
        type,
        userId,
        "Ana",
        "Diaz",
        "ana",
        "ana@example.org",
        "+50688887777",
        true,
        "203.0.113.9",
        Locale.ENGLISH,
        context);
  }

  @Test
  void everyEventOfTheHistoryIsMappedToTheRightActivity() {
    final Map<SelfServiceNotificationEvent.Type, ActivityType> expected =
        new EnumMap<>(SelfServiceNotificationEvent.Type.class);
    expected.put(SelfServiceNotificationEvent.Type.LOGIN_SUCCESS, ActivityType.LOGIN);
    expected.put(SelfServiceNotificationEvent.Type.LOGIN_FAILURE, ActivityType.LOGIN_FAILED);
    expected.put(
        SelfServiceNotificationEvent.Type.LOGIN_UNKNOWN_DEVICE, ActivityType.LOGIN_NEW_DEVICE);
    expected.put(SelfServiceNotificationEvent.Type.LOGOUT, ActivityType.LOGOUT);
    expected.put(SelfServiceNotificationEvent.Type.PASSWORD_RENEWED, ActivityType.PASSWORD_RESET);
    expected.put(
        SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS, ActivityType.TRANSFER_CONFIRMED);
    expected.put(
        SelfServiceNotificationEvent.Type.BENEFICIARY_ADDED, ActivityType.BENEFICIARY_ADDED);
    expected.put(
        SelfServiceNotificationEvent.Type.BENEFICIARY_UPDATED, ActivityType.BENEFICIARY_UPDATED);
    expected.put(
        SelfServiceNotificationEvent.Type.BENEFICIARY_DELETED, ActivityType.BENEFICIARY_DELETED);
    expected.put(
        SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_CREATED,
        ActivityType.SINPE_SUBSCRIPTION_CHANGED);
    expected.put(
        SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_UPDATED,
        ActivityType.SINPE_SUBSCRIPTION_CHANGED);
    expected.put(
        SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_DELETED,
        ActivityType.SINPE_SUBSCRIPTION_CHANGED);

    expected.forEach(
        (eventType, activityType) ->
            assertEquals(
                activityType,
                ActivityEventListener.map(eventType).orElseThrow().type(),
                eventType.name()));
  }

  @Test
  void aFailedLogin_isAFailure_everythingElseASuccess() {
    assertEquals(
        ActivityOutcome.FAILURE,
        ActivityEventListener.map(SelfServiceNotificationEvent.Type.LOGIN_FAILURE)
            .orElseThrow()
            .outcome());
    assertEquals(
        ActivityOutcome.SUCCESS,
        ActivityEventListener.map(SelfServiceNotificationEvent.Type.LOGIN_SUCCESS)
            .orElseThrow()
            .outcome());
  }

  @Test
  void eventsThatAreNotPartOfTheHistoryAreIgnored() {
    for (final SelfServiceNotificationEvent.Type type :
        new SelfServiceNotificationEvent.Type[] {
          SelfServiceNotificationEvent.Type.USER_ACTIVATED,
          SelfServiceNotificationEvent.Type.ENROLLMENT_TOKEN,
          SelfServiceNotificationEvent.Type.TRANSFER_OTP,
          SelfServiceNotificationEvent.Type.STEP_UP_OTP,
          SelfServiceNotificationEvent.Type.SAVINGS_DEPOSIT,
          SelfServiceNotificationEvent.Type.LOAN_REQUESTED,
          SelfServiceNotificationEvent.Type.PASSWORD_RESET_REQUESTED
        }) {
      listener.onNotificationEvent(event(type, 7L, Map.of()));
    }

    verify(activityLogService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void aTransferEvent_passesItsFactsTheUserIdAndTheIp() {
    final Map<String, Object> context = Map.of("transactionAmount", "10", "currencyCode", "USD");

    listener.onNotificationEvent(
        event(SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS, 7L, context));

    final ArgumentCaptor<Map<String, Object>> source = ArgumentCaptor.forClass(Map.class);
    verify(activityLogService)
        .record(
            eq(ActivityType.TRANSFER_CONFIRMED),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            source.capture());
    assertEquals("10", source.getValue().get("transactionAmount"));
  }

  @Test
  void aSinpeEvent_tellsWhatHappenedToTheSubscription() {
    listener.onNotificationEvent(
        event(SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_DELETED, 7L, Map.of()));

    final ArgumentCaptor<Map<String, Object>> source = ArgumentCaptor.forClass(Map.class);
    verify(activityLogService)
        .record(
            eq(ActivityType.SINPE_SUBSCRIPTION_CHANGED),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            source.capture());
    assertEquals("deleted", source.getValue().get("action"));
  }

  @Test
  void anEventWithoutAUser_isIgnored() {
    listener.onNotificationEvent(
        event(SelfServiceNotificationEvent.Type.LOGIN_SUCCESS, null, Map.of()));

    verify(activityLogService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void aFailureInsideTheLog_neverReachesThePublisher() {
    doThrow(new IllegalStateException("boom"))
        .when(activityLogService)
        .record(any(), any(), any(), any(), any());

    assertDoesNotThrow(
        () ->
            listener.onNotificationEvent(
                event(SelfServiceNotificationEvent.Type.LOGIN_SUCCESS, 7L, Map.of())));
  }

  // --- step-up ----------------------------------------------------------------------------------

  private static StepUpEvent stepUp(final StepUpEvent.Type type) {
    return new StepUpEvent(
        type,
        11L,
        7L,
        StepUpAction.TPT_TRANSFER,
        StepUpChannel.EMAIL,
        "otp.mismatch",
        LocalDateTime.of(2026, 10, 23, 9, 0),
        "203.0.113.9");
  }

  @Test
  void stepUp_requestedVerifiedAndLocked_areRecorded() {
    assertEquals(
        ActivityType.STEP_UP_REQUESTED,
        ActivityEventListener.mapStepUp(StepUpEvent.Type.ISSUED).orElseThrow().type());
    assertEquals(
        ActivityType.STEP_UP_VERIFIED,
        ActivityEventListener.mapStepUp(StepUpEvent.Type.VERIFIED).orElseThrow().type());
    final ActivityEventListener.Mapped locked =
        ActivityEventListener.mapStepUp(StepUpEvent.Type.LOCKED).orElseThrow();
    assertEquals(ActivityType.STEP_UP_LOCKED, locked.type());
    assertEquals(ActivityOutcome.FAILURE, locked.outcome());
  }

  @Test
  void stepUp_wrongAttemptsResendsAndTheRestAreLeftOut_toKeepTheFeedReadable() {
    for (final StepUpEvent.Type type :
        new StepUpEvent.Type[] {
          StepUpEvent.Type.FAILED,
          StepUpEvent.Type.RESENT,
          StepUpEvent.Type.CONSUMED,
          StepUpEvent.Type.REJECTED
        }) {
      assertTrue(ActivityEventListener.mapStepUp(type).isEmpty(), type.name());
      listener.onStepUpEvent(stepUp(type));
    }

    verify(activityLogService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void stepUp_carriesTheActionButNeverTheReasonOrTheChallengeId() {
    listener.onStepUpEvent(stepUp(StepUpEvent.Type.VERIFIED));

    final ArgumentCaptor<Map<String, Object>> source = ArgumentCaptor.forClass(Map.class);
    verify(activityLogService)
        .record(
            eq(ActivityType.STEP_UP_VERIFIED),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            source.capture());
    assertEquals(Map.of("action", "TPT_TRANSFER"), source.getValue());
  }

  @Test
  void stepUp_aFailureInsideTheLog_neverReachesThePublisher() {
    doThrow(new IllegalStateException("boom"))
        .when(activityLogService)
        .record(any(), any(), any(), any(), any());

    assertDoesNotThrow(() -> listener.onStepUpEvent(stepUp(StepUpEvent.Type.ISSUED)));
  }

  // ---- authenticator app ----------------------------------------------------------------------

  private static TotpEvent totp(final TotpEvent.Type type, final Long userId) {
    return new TotpEvent(type, userId, "reason", LocalDateTime.of(2026, 10, 28, 12, 0), "10.0.0.1");
  }

  @Test
  void totp_everyEventOfTheAuthenticatorIsMappedToTheRightActivity() {
    final Map<TotpEvent.Type, ActivityType> expected = new EnumMap<>(TotpEvent.Type.class);
    expected.put(TotpEvent.Type.ENROLLED, ActivityType.AUTHENTICATOR_ENROLLED);
    expected.put(TotpEvent.Type.REMOVED, ActivityType.AUTHENTICATOR_REMOVED);
    expected.put(TotpEvent.Type.RESET_BY_STAFF, ActivityType.AUTHENTICATOR_RESET);
    expected.put(TotpEvent.Type.LOCKED, ActivityType.AUTHENTICATOR_LOCKED);
    expected.put(
        TotpEvent.Type.RECOVERY_CODES_REGENERATED, ActivityType.RECOVERY_CODES_REGENERATED);
    expected.put(TotpEvent.Type.RECOVERY_CODE_USED, ActivityType.RECOVERY_CODE_USED);

    // a new event type must be mapped on purpose, not forgotten
    assertEquals(expected.keySet(), java.util.EnumSet.allOf(TotpEvent.Type.class));
    for (final Map.Entry<TotpEvent.Type, ActivityType> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          ActivityEventListener.mapTotp(entry.getKey()).orElseThrow().type(),
          entry.getKey().name());
    }
  }

  @Test
  void totp_aLockOutIsAFailure_everythingElseASuccess() {
    for (final TotpEvent.Type type : TotpEvent.Type.values()) {
      assertEquals(
          type == TotpEvent.Type.LOCKED ? ActivityOutcome.FAILURE : ActivityOutcome.SUCCESS,
          ActivityEventListener.mapTotp(type).orElseThrow().outcome(),
          type.name());
    }
  }

  @Test
  void totp_aUserChangeIsRecordedWithTheAddressOfTheConnection_andNoDetails() {
    listener.onTotpEvent(totp(TotpEvent.Type.ENROLLED, 7L));

    verify(activityLogService)
        .record(
            eq(ActivityType.AUTHENTICATOR_ENROLLED),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            eq(Map.of()));
    verify(activityLogService, never()).recordWithoutAddress(any(), any(), any(), any(), any());
  }

  @Test
  void totp_aStaffReset_isRecordedWithoutAnAddress_becauseTheRequestIsTheStaffMembers() {
    listener.onTotpEvent(totp(TotpEvent.Type.RESET_BY_STAFF, 7L));

    verify(activityLogService)
        .recordWithoutAddress(
            eq(ActivityType.AUTHENTICATOR_RESET),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            eq(Map.of()));
    verify(activityLogService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void totp_theReasonAndTheAddressOfTheEventAreNeverPassedOn() {
    listener.onTotpEvent(totp(TotpEvent.Type.LOCKED, 7L));

    final ArgumentCaptor<Map<String, Object>> source = ArgumentCaptor.forClass(Map.class);
    verify(activityLogService)
        .record(any(), any(), eq(7L), isNull(), source.capture());
    assertTrue(source.getValue().isEmpty());
  }

  @Test
  void totp_anEventWithoutAUser_isIgnored() {
    listener.onTotpEvent(totp(TotpEvent.Type.ENROLLED, null));
    verify(activityLogService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void totp_aFailureInsideTheLog_neverReachesThePublisher() {
    doThrow(new IllegalStateException("boom"))
        .when(activityLogService)
        .record(any(), any(), any(), any(), any());
    doThrow(new IllegalStateException("boom"))
        .when(activityLogService)
        .recordWithoutAddress(any(), any(), any(), any(), any());

    assertDoesNotThrow(() -> listener.onTotpEvent(totp(TotpEvent.Type.REMOVED, 7L)));
    assertDoesNotThrow(() -> listener.onTotpEvent(totp(TotpEvent.Type.RESET_BY_STAFF, 7L)));
  }

  @Test
  void theIpAddressOfAnEventIsNeverUsed_itComesFromAHeaderAClientCanForge() {
    listener.onNotificationEvent(
        event(SelfServiceNotificationEvent.Type.LOGIN_SUCCESS, 7L, Map.of("ipAddress", "6.6.6.6")));

    final ArgumentCaptor<Map<String, Object>> source = ArgumentCaptor.forClass(Map.class);
    verify(activityLogService)
        .record(
            eq(ActivityType.LOGIN),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            source.capture());
    // the whole context is handed over, but only whitelisted keys can ever be stored
    assertEquals(null, ActivityDetails.toJson(ActivityType.LOGIN, source.getValue()));
  }
}
