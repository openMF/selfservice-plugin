/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepUpGuardTest {

  private static final String SESSION_TOKEN = "11111111-2222-3333-4444-555555555555";

  @Mock private StepUpSettingsProvider settingsProvider;
  @Mock private StepUpChallengeService challengeService;
  @Mock private PlatformSelfServiceSecurityContext securityContext;
  @Mock private HttpServletRequest request;

  private StepUpGuard guard;

  private static String basic(final String raw) {
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @BeforeEach
  void setUp() {
    guard = new StepUpGuard(settingsProvider, challengeService, securityContext);
    final AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getId()).thenReturn(7L);
    when(securityContext.authenticatedSelfServiceUser()).thenReturn(user);
    when(settingsProvider.current())
        .thenReturn(StepUpSettings.fromProperties(Map.of("enabled", "true")));
    when(request.getHeader("Authorization")).thenReturn(basic(SESSION_TOKEN));
    when(request.getHeader("X-Step-Up-Token")).thenReturn("grant-token");
    when(request.getRemoteAddr()).thenReturn("10.0.0.9");
  }

  @Test
  void whenDisabled_doesNothing_andNeverEvaluatesThePayload() {
    when(settingsProvider.current()).thenReturn(StepUpSettings.defaults());
    final AtomicInteger evaluated = new AtomicInteger();

    guard.requireIfProtected(
        StepUpAction.TPT_TRANSFER,
        request,
        () -> {
          evaluated.incrementAndGet();
          return "hash";
        });

    assertEquals(0, evaluated.get());
    verifyNoInteractions(challengeService);
    assertFalse(guard.isProtected(StepUpAction.TPT_TRANSFER));
  }

  @Test
  void whenTheActionIsNotProtected_doesNothing() {
    when(settingsProvider.current())
        .thenReturn(
            StepUpSettings.fromProperties(
                Map.of("enabled", "true", "protectedActions", "CHANGE_PASSWORD")));
    when(request.getHeader("X-Step-Up-Token")).thenReturn(null);

    guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "hash");

    verifyNoInteractions(challengeService);
    assertTrue(guard.isProtected(StepUpAction.CHANGE_PASSWORD));
  }

  @Test
  void withoutAToken_isRejectedAsRequired_withTheAction() {
    when(request.getHeader("X-Step-Up-Token")).thenReturn(null);

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "hash"));

    assertEquals("error.msg.selfservice.stepup.required", ex.getErrorCode());
    assertEquals(403, ex.getHttpStatus());
    assertEquals("TPT_TRANSFER", ex.getDetails().get("action"));
    verifyNoInteractions(challengeService);
  }

  @Test
  void aBlankToken_isTreatedAsMissing() {
    when(request.getHeader("X-Step-Up-Token")).thenReturn("   ");
    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> guard.requireIfProtected(StepUpAction.CHANGE_PASSWORD, request, () -> "hash"));
    assertEquals("error.msg.selfservice.stepup.required", ex.getErrorCode());
  }

  @Test
  void credentialBasedAuthentication_hasNoSession_andIsRefused() {
    when(request.getHeader("Authorization")).thenReturn(basic("ana:secret"));

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "hash"));

    assertEquals("error.msg.selfservice.stepup.session.required", ex.getErrorCode());
    verifyNoInteractions(challengeService);
  }

  @Test
  void aMissingAuthorizationHeader_hasNoSession_andIsRefused() {
    when(request.getHeader("Authorization")).thenReturn(null);
    assertThrows(
        StepUpException.class,
        () -> guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "hash"));
  }

  @Test
  void withATokenAndASession_consumesTheGrantForTheRealRequestDigest() {
    guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "digest-of-real-request");

    verify(challengeService)
        .consumeGrant(
            "grant-token",
            7L,
            StepUpCrypto.sha256Hex(SESSION_TOKEN),
            StepUpAction.TPT_TRANSFER,
            "digest-of-real-request",
            "10.0.0.9");
  }

  @Test
  void theSessionIsOnlyEverHandedOverAsADigest() {
    guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "d");

    verify(challengeService, never())
        .consumeGrant(
            any(), any(), org.mockito.ArgumentMatchers.eq(SESSION_TOKEN), any(), any(), any());
  }

  @Test
  void aRejectedGrant_propagatesAsInvalid() {
    doThrow(StepUpException.invalid())
        .when(challengeService)
        .consumeGrant(any(), any(), any(), any(), any(), any());

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> guard.requireIfProtected(StepUpAction.TPT_TRANSFER, request, () -> "d"));
    assertEquals("error.msg.selfservice.stepup.invalid", ex.getErrorCode());
  }

  @Test
  void requestSupport_parsesSessionAndRejectsMalformedHeaders() {
    when(request.getHeader("Authorization")).thenReturn("Basic !!!not-base64!!!");
    assertTrue(StepUpRequestSupport.sessionHash(request).isEmpty());

    when(request.getHeader("Authorization")).thenReturn("Bearer abc");
    assertTrue(StepUpRequestSupport.sessionHash(request).isEmpty());

    // the "Basic" scheme name is matched case-insensitively
    when(request.getHeader("Authorization"))
        .thenReturn("basic " + basic(SESSION_TOKEN).substring(6));
    assertEquals(
        StepUpCrypto.sha256Hex(SESSION_TOKEN), StepUpRequestSupport.sessionHash(request).get());
  }
}
