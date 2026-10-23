/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.selfservice.activity.domain.ActivityOutcome;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.service.ActivityLogService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService;
import org.apache.fineract.selfservice.security.service.SelfUserPasswordChangeService;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpGuard;
import org.apache.fineract.selfservice.stepup.service.StepUpPayloadHasher;
import org.apache.fineract.selfservice.stepup.service.StepUpSettings;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Step-up and session invalidation on {@code PUT /v1/self/user} (password change). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfUserApiResourceStepUpTest {

  private static final String BODY =
      "{\"password\":\"N3w-Passw0rd!\",\"repeatPassword\":\"N3w-Passw0rd!\"}";

  @Mock private SelfUserPasswordChangeService passwordChangeService;
  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private StepUpGuard stepUpGuard;
  @Mock private StepUpSettingsProvider settingsProvider;
  @Mock private SelfServiceAuthenticationTokenService tokenService;
  @Mock private ActivityLogService activityLogService;
  @Mock private HttpServletRequest request;
  @Mock private AppSelfServiceUser user;

  private SelfUserApiResource resource;

  @BeforeEach
  void setUp() {
    resource =
        new SelfUserApiResource(
            context,
            passwordChangeService,
            stepUpGuard,
            settingsProvider,
            tokenService,
            activityLogService);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
    when(user.getId()).thenReturn(7L);
    when(passwordChangeService.changePassword(user, BODY)).thenReturn("{\"resourceId\":7}");
    when(settingsProvider.current())
        .thenReturn(StepUpSettings.fromProperties(Map.of("enabled", "true")));
  }

  @Test
  void passwordChange_isGatedByStepUpBeforeTheUserIsUpdated() {
    final String response = resource.update(BODY, request);

    assertEquals("{\"resourceId\":7}", response);
    final InOrder order = inOrder(passwordChangeService, stepUpGuard);
    order.verify(passwordChangeService).validateRequest(BODY);
    order
        .verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.CHANGE_PASSWORD), eq(request), any());
    order.verify(passwordChangeService).changePassword(user, BODY);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theStepUpBinding_isTheActionOnly_neverThePassword() {
    resource.update(BODY, request);

    final ArgumentCaptor<Supplier<String>> hash = ArgumentCaptor.forClass(Supplier.class);
    verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.CHANGE_PASSWORD), eq(request), hash.capture());
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.CHANGE_PASSWORD, Map.of()), hash.getValue().get());
  }

  @Test
  void withoutAValidStepUp_thePasswordIsNotChanged_andNoSessionIsRevoked() {
    doThrow(StepUpException.required(StepUpAction.CHANGE_PASSWORD))
        .when(stepUpGuard)
        .requireIfProtected(any(), any(), any());

    final StepUpException ex =
        assertThrows(StepUpException.class, () -> resource.update(BODY, request));

    assertEquals("error.msg.selfservice.stepup.required", ex.getErrorCode());
    verify(passwordChangeService, never()).changePassword(any(), any());
    verifyNoInteractions(tokenService);
  }

  @Test
  void aSuccessfulChange_revokesEverySessionOfTheUser_soTheyMustLogInAgain() {
    resource.update(BODY, request);

    verify(tokenService).invalidateAllForUser(7L);
  }

  @Test
  void ifTheUpdateFails_sessionsAreKept() {
    when(passwordChangeService.changePassword(user, BODY))
        .thenThrow(new IllegalStateException("boom"));

    assertThrows(IllegalStateException.class, () -> resource.update(BODY, request));

    verify(tokenService, never()).invalidateAllForUser(any());
  }

  @Test
  void whenStepUpIsOffAndNothingIsConfigured_behaviourIsExactlyAsBefore() {
    when(settingsProvider.current()).thenReturn(StepUpSettings.defaults());

    resource.update(BODY, request);

    verify(passwordChangeService).changePassword(user, BODY);
    verify(tokenService, never()).invalidateAllForUser(any());
  }

  @Test
  void sessionInvalidation_canBeSwitchedOffExplicitly() {
    when(settingsProvider.current())
        .thenReturn(
            StepUpSettings.fromProperties(
                Map.of("enabled", "true", "invalidateSessionsOnPasswordChange", "false")));

    resource.update(BODY, request);

    verify(tokenService, never()).invalidateAllForUser(any());
  }

  @Test
  void aMalformedRequest_isRejectedBeforeStepUpIsConsumed() {
    doThrow(new InvalidJsonException()).when(passwordChangeService).validateRequest(any());

    assertThrows(
        InvalidJsonException.class, () -> resource.update("{\"password\":\"a\"}", request));

    verifyNoInteractions(stepUpGuard, tokenService);
    verify(passwordChangeService, never()).changePassword(any(), any());
  }

  @Test
  void aSuccessfulChange_isRecordedInTheActivityHistory_withoutThePassword() {
    resource.update(BODY, request);

    verify(activityLogService)
        .record(
            eq(ActivityType.PASSWORD_CHANGED),
            eq(ActivityOutcome.SUCCESS),
            eq(7L),
            isNull(),
            any(),
            isNull());
  }

  @Test
  void aRefusedChange_isNotRecordedAsDone() {
    doThrow(StepUpException.required(StepUpAction.CHANGE_PASSWORD))
        .when(stepUpGuard)
        .requireIfProtected(any(), any(), any());

    assertThrows(StepUpException.class, () -> resource.update(BODY, request));

    verifyNoInteractions(activityLogService);
  }
}
