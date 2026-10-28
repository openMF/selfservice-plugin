/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.stepup.data.TotpEnrollmentData;
import org.apache.fineract.selfservice.stepup.data.TotpRecoveryCodesData;
import org.apache.fineract.selfservice.stepup.data.TotpStatusData;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpGuard;
import org.apache.fineract.selfservice.stepup.service.StepUpPayloadHasher;
import org.apache.fineract.selfservice.stepup.totp.TotpService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfTotpApiResourceTest {

  private static final Long USER = 7L;

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private TotpService totpService;
  @Mock private StepUpGuard stepUpGuard;
  @Mock private HttpServletRequest request;
  @Mock private AppSelfServiceUser user;

  private SelfTotpApiResource resource;

  @BeforeEach
  void setUp() {
    resource =
        new SelfTotpApiResource(
            context, totpService, new StepUpDataValidator(new FromJsonHelper()), stepUpGuard);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
    when(user.getId()).thenReturn(USER);
    when(user.getUsername()).thenReturn("ana");
    when(request.getRemoteAddr()).thenReturn("10.0.0.9");
  }

  private static JsonObject json(final String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  /** Runs the step-up check the resource handed to the service, as the service would. */
  private static Runnable capturedCheck(final ArgumentCaptor<Runnable> captor) {
    return captor.getValue();
  }

  @Test
  void status_returnsTheStatusOfTheCaller() {
    when(totpService.status(USER)).thenReturn(new TotpStatusData(true, "ACTIVE", 8, true, "t"));

    final JsonObject body = json(resource.status());

    assertEquals("ACTIVE", body.get("status").getAsString());
    assertEquals(8, body.get("recoveryCodesRemaining").getAsInt());
    assertTrue(body.get("available").getAsBoolean());
    assertTrue(body.get("exclusive").getAsBoolean());
  }

  @Test
  void beginEnrollment_answers201_withTheSecretOnce_forTheCallerOnly() {
    when(totpService.beginEnrollment(USER, "ana"))
        .thenReturn(new TotpEnrollmentData("JBSWY3DPEHPK3PXP", "otpauth://totp/x", "Mifos", 6, 30));

    final Response response = resource.beginEnrollment();

    assertEquals(201, response.getStatus());
    final JsonObject body = json((String) response.getEntity());
    assertEquals("JBSWY3DPEHPK3PXP", body.get("secret").getAsString());
    assertEquals("otpauth://totp/x", body.get("otpauthUri").getAsString());
    assertEquals(6, body.get("digits").getAsInt());
    assertEquals(30, body.get("periodSeconds").getAsInt());
    verify(totpService).beginEnrollment(USER, "ana");
  }

  @Test
  void confirm_passesTheCodeAndTheConnectionAddress_andReturnsTheRecoveryCodes() {
    when(totpService.confirmEnrollment(eq(USER), eq("123456"), eq("10.0.0.9"), any()))
        .thenReturn(new TotpRecoveryCodesData(List.of("ABCDE-FGHJK", "KMNPQ-RSTUV")));

    final JsonObject body = json(resource.confirmEnrollment("{\"code\":\" 123456 \"}", request));

    assertEquals(2, body.getAsJsonArray("recoveryCodes").size());
    assertEquals("ABCDE-FGHJK", body.getAsJsonArray("recoveryCodes").get(0).getAsString());
  }

  @Test
  void confirm_theStepUpCheckIsBoundToTheActionOnly() {
    when(totpService.confirmEnrollment(any(), any(), any(), any()))
        .thenReturn(new TotpRecoveryCodesData(List.of()));

    resource.confirmEnrollment("{\"code\":\"123456\"}", request);

    final ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
    verify(totpService).confirmEnrollment(any(), any(), any(), check.capture());
    // nothing was checked yet: the service decides when
    verify(stepUpGuard, never()).requireIfProtected(any(), any(), any());

    capturedCheck(check).run();

    final AtomicReference<String> hash = new AtomicReference<>();
    final ArgumentCaptor<java.util.function.Supplier<String>> payload =
        ArgumentCaptor.forClass(java.util.function.Supplier.class);
    verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.MANAGE_AUTHENTICATOR), eq(request), payload.capture());
    hash.set(payload.getValue().get());
    assertEquals(StepUpPayloadHasher.hash(StepUpAction.MANAGE_AUTHENTICATOR, Map.of()), hash.get());
  }

  @Test
  void confirm_aRefusedStepUpCheck_reachesTheCaller() {
    org.mockito.Mockito.doThrow(StepUpException.required(StepUpAction.MANAGE_AUTHENTICATOR))
        .when(stepUpGuard)
        .requireIfProtected(any(), any(), any());
    when(totpService.confirmEnrollment(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(3)).run();
              return new TotpRecoveryCodesData(List.of());
            });

    final StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> resource.confirmEnrollment("{\"code\":\"123456\"}", request));

    assertEquals("error.msg.selfservice.stepup.required", ex.getErrorCode());
  }

  @Test
  void confirm_rejectsABlankMissingOrUnknownBody_beforeTouchingTheService() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> resource.confirmEnrollment("{\"code\":\"\"}", request));
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.InvalidJsonException.class,
        () -> resource.confirmEnrollment("", request));
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException.class,
        () -> resource.confirmEnrollment("{\"code\":\"123456\",\"userId\":9}", request));
    verify(totpService, never()).confirmEnrollment(any(), any(), any(), any());
  }

  @Test
  void recoveryCodes_areReturnedOnceForTheCaller_behindTheStepUpCheck() {
    when(totpService.regenerateRecoveryCodes(eq(USER), eq("10.0.0.9"), any()))
        .thenReturn(new TotpRecoveryCodesData(List.of("ABCDE-FGHJK")));

    final JsonObject body = json(resource.regenerateRecoveryCodes(request));

    assertEquals("ABCDE-FGHJK", body.getAsJsonArray("recoveryCodes").get(0).getAsString());
    final ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
    verify(totpService).regenerateRecoveryCodes(eq(USER), eq("10.0.0.9"), check.capture());
    capturedCheck(check).run();
    verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.MANAGE_AUTHENTICATOR), eq(request), any());
  }

  @Test
  void remove_answersRemoved_behindTheStepUpCheck() {
    final Response response = resource.remove(request);

    assertEquals(200, response.getStatus());
    assertTrue(json((String) response.getEntity()).get("removed").getAsBoolean());
    final ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
    verify(totpService).remove(eq(USER), eq("10.0.0.9"), check.capture());
    capturedCheck(check).run();
    verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.MANAGE_AUTHENTICATOR), eq(request), any());
  }

  @Test
  void everyCallActsOnTheLoggedInUserOnly_thereIsNoUserIdParameter() {
    resource.status();
    resource.beginEnrollment();
    resource.remove(request);
    resource.regenerateRecoveryCodes(request);

    verify(totpService).status(USER);
    verify(totpService).beginEnrollment(USER, "ana");
    verify(totpService).remove(eq(USER), any(), any());
    verify(totpService).regenerateRecoveryCodes(eq(USER), any(), any());
    assertFalse(
        List.of(SelfTotpApiResource.class.getDeclaredMethods()).stream()
            .flatMap(m -> java.util.Arrays.stream(m.getParameters()))
            .anyMatch(p -> p.getName().toLowerCase(java.util.Locale.ROOT).contains("userid")));
    // the body of the enrolment confirmation carries only the code
    assertEquals(java.util.Set.of("code"), StepUpApiConstants.CONFIRM_TOTP_PARAMETERS);
  }
}
