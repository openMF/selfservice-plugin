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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.stepup.data.StepUpChallengeData;
import org.apache.fineract.selfservice.stepup.data.StepUpGrantData;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.AuthorizationHeaderStepUpSessionResolver;
import org.apache.fineract.selfservice.stepup.service.StepUpChallengeService;
import org.apache.fineract.selfservice.stepup.service.StepUpContact;
import org.apache.fineract.selfservice.stepup.service.StepUpCrypto;
import org.apache.fineract.selfservice.stepup.service.StepUpSettings;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
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
class SelfStepUpApiResourceTest {

  private static final String SESSION_TOKEN = "11111111-2222-3333-4444-555555555555";
  private static final String SESSION_HASH = StepUpCrypto.sha256Hex(SESSION_TOKEN);

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private StepUpChallengeService challengeService;
  @Mock private StepUpSettingsProvider settingsProvider;
  @Mock private HttpServletRequest request;
  @Mock private AppSelfServiceUser user;

  private SelfStepUpApiResource resource;

  private static String basic(final String raw) {
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @BeforeEach
  void setUp() {
    resource =
        new SelfStepUpApiResource(
            context,
            challengeService,
            settingsProvider,
            new StepUpDataValidator(new FromJsonHelper()),
            new AuthorizationHeaderStepUpSessionResolver());
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
    when(user.getId()).thenReturn(7L);
    when(user.getUsername()).thenReturn("ana");
    when(user.getEmail()).thenReturn("ana@example.com");
    when(settingsProvider.current())
        .thenReturn(StepUpSettings.fromProperties(Map.of("enabled", "true")));
    when(request.getHeader("Authorization")).thenReturn(basic(SESSION_TOKEN));
    when(request.getRemoteAddr()).thenReturn("10.0.0.9");
  }

  private static final String CREATE_BODY =
      "{\"action\":\"TPT_TRANSFER\",\"channel\":\"EMAIL\",\"payload\":{\"fromAccountId\":1,"
          + "\"fromAccountType\":2,\"toAccountId\":2,\"toAccountType\":2,\"transferAmount\":100}}";

  @Test
  void createChallenge_answers201_withoutEverReturningTheOtp() {
    final StepUpChallengeData data =
        new StepUpChallengeData(
            5L,
            "TPT_TRANSFER",
            "EMAIL",
            "a***@example.com",
            "2026-10-21T12:05:00",
            5,
            "2026-10-21T12:01:00",
            3);
    when(challengeService.createChallenge(any(), any(), any(), any(), anyMap(), any(), any()))
        .thenReturn(data);

    final Response response = resource.createChallenge(CREATE_BODY, request);

    assertEquals(201, response.getStatus());
    final JsonObject body = JsonParser.parseString((String) response.getEntity()).getAsJsonObject();
    assertEquals(5L, body.get("challengeId").getAsLong());
    assertEquals("a***@example.com", body.get("maskedDestination").getAsString());
    assertEquals(5, body.get("attemptsRemaining").getAsInt());
    assertFalse(body.has("otp"));
    assertFalse(body.has("authCode"));
    assertFalse(((String) response.getEntity()).contains("ana@example.com"));
  }

  @Test
  void createChallenge_bindsTheChallengeToTheSessionDigestUserContactAndPayload() {
    when(challengeService.createChallenge(any(), any(), any(), any(), anyMap(), any(), any()))
        .thenReturn(new StepUpChallengeData(1L, "X", "EMAIL", "m", "e", 1, "r", 1));

    resource.createChallenge(CREATE_BODY, request);

    final ArgumentCaptor<StepUpContact> contact = ArgumentCaptor.forClass(StepUpContact.class);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
    verify(challengeService)
        .createChallenge(
            contact.capture(),
            eq(SESSION_HASH),
            eq(StepUpAction.TPT_TRANSFER),
            eq(StepUpChannel.EMAIL),
            payload.capture(),
            eq(Locale.forLanguageTag("es-MX")),
            eq("10.0.0.9"));
    assertEquals(7L, contact.getValue().userId());
    assertEquals("ana@example.com", contact.getValue().email());
    assertEquals("100", payload.getValue().get("transferAmount"));
  }

  @Test
  void everyEndpoint_requiresASessionToken_notUserAndPassword() {
    when(request.getHeader("Authorization")).thenReturn(basic("ana:secret"));

    assertEquals(
        "error.msg.selfservice.stepup.session.required",
        assertThrows(StepUpException.class, () -> resource.createChallenge(CREATE_BODY, request))
            .getErrorCode());
    assertEquals(
        "error.msg.selfservice.stepup.session.required",
        assertThrows(
                StepUpException.class, () -> resource.verify(5L, "{\"otp\":\"123456\"}", request))
            .getErrorCode());
    assertEquals(
        "error.msg.selfservice.stepup.session.required",
        assertThrows(StepUpException.class, () -> resource.resend(5L, request)).getErrorCode());
    verify(challengeService, never()).verify(any(), any(), any(), any(), any());
  }

  @Test
  void verify_returnsTheGrantAndScopesTheLookupToUserAndSession() {
    when(challengeService.verify(any(), any(), any(), any(), any()))
        .thenReturn(
            new StepUpGrantData("grant-token-value", "TPT_TRANSFER", "2026-10-21T12:02:00"));

    final String json = resource.verify(5L, "{\"otp\":\"123456\"}", request);

    final JsonObject body = JsonParser.parseString(json).getAsJsonObject();
    assertEquals("grant-token-value", body.get("stepUpToken").getAsString());
    assertEquals("TPT_TRANSFER", body.get("action").getAsString());
    verify(challengeService).verify(eq(5L), eq("123456"), eq(7L), eq(SESSION_HASH), eq("10.0.0.9"));
  }

  @Test
  void resend_scopesTheLookupToUserAndSession() {
    when(challengeService.resend(any(), any(), any(), any(), any()))
        .thenReturn(new StepUpChallengeData(5L, "X", "SMS", "m", "e", 4, "r", 2));

    final JsonObject body = JsonParser.parseString(resource.resend(5L, request)).getAsJsonObject();

    assertEquals(4, body.get("attemptsRemaining").getAsInt());
    verify(challengeService)
        .resend(eq(5L), any(StepUpContact.class), eq(SESSION_HASH), any(), eq("10.0.0.9"));
  }

  @Test
  void config_listsOnlyTheChannelsTheUserCanActuallyUse() {
    // The user has an e-mail but no mobile on file (the mocked mappings are empty).
    final JsonObject body = JsonParser.parseString(resource.config(request)).getAsJsonObject();

    assertTrue(body.get("enabled").getAsBoolean());
    assertEquals(1, body.getAsJsonArray("availableChannels").size());
    assertEquals("EMAIL", body.getAsJsonArray("availableChannels").get(0).getAsString());
    assertTrue(body.get("sessionBound").getAsBoolean());
    assertTrue(body.getAsJsonArray("protectedActions").toString().contains("TPT_TRANSFER"));
    assertEquals(6, body.get("otpLength").getAsInt());
    assertEquals(120, body.get("grantTtlSeconds").getAsInt());
  }

  @Test
  void config_whenDisabled_saysSoAndFlagsCredentialBasedCallers() {
    when(settingsProvider.current()).thenReturn(StepUpSettings.defaults());
    when(request.getHeader("Authorization")).thenReturn(basic("ana:secret"));

    final JsonObject body = JsonParser.parseString(resource.config(request)).getAsJsonObject();

    assertFalse(body.get("enabled").getAsBoolean());
    assertFalse(body.get("sessionBound").getAsBoolean());
  }
}
