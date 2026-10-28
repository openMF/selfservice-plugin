/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.stepup.data.StepUpChallengeData;
import org.apache.fineract.selfservice.stepup.data.StepUpConfigData;
import org.apache.fineract.selfservice.stepup.data.StepUpGrantData;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpChallengeService;
import org.apache.fineract.selfservice.stepup.service.StepUpContact;
import org.apache.fineract.selfservice.stepup.service.StepUpRequestSupport;
import org.apache.fineract.selfservice.stepup.service.StepUpSessionResolver;
import org.apache.fineract.selfservice.stepup.service.StepUpSettings;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
import org.apache.fineract.selfservice.stepup.totp.TotpService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

/**
 * Step-up (OTP) authentication for sensitive self-service operations (MX-488, US-E12-02).
 *
 * <p>Flow: {@code POST /challenges} (an OTP is sent) → {@code POST /challenges/{id}/verify}
 * (returns a one-time {@code stepUpToken}) → the protected request carries {@code X-Step-Up-Token}.
 *
 * <p>All endpoints require a logged-in self-service user and a session token ({@code Authorization:
 * Basic base64(accessToken)}); a challenge can only be used by the user and session that created
 * it. Responses never contain the OTP.
 */
@Path("/v1/self/stepup")
@Component
@Tag(
    name = "Self Step-up Authentication",
    description =
        "One-time-code challenges that must be passed before sensitive operations (third-party"
            + " transfers, transfer confirmation, password change) when enabled for the tenant.")
@RequiredArgsConstructor
public class SelfStepUpApiResource {

  private static final Gson GSON = new Gson();

  private final PlatformSelfServiceSecurityContext context;
  private final StepUpChallengeService challengeService;
  private final StepUpSettingsProvider settingsProvider;
  private final StepUpDataValidator dataValidator;
  private final StepUpSessionResolver sessionResolver;
  private final TotpService totpService;

  @GET
  @Path("/config")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Step-up configuration for the current user",
      description =
          "Tells the client whether step-up is enabled, which actions are protected and which"
              + " delivery channels the current user can actually use, so the UI can run the flow"
              + " before the first protected call.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(implementation = StepUpApiResourceSwagger.StepUpConfigResponse.class)))
  public String config(@Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    final StepUpContact contact = StepUpContact.from(user);
    final StepUpSettings settings = settingsProvider.current();
    // The authenticator has no contact: it is offered when the tenant allows it and the user paired
    // one. While it is active and exclusive, SMS and e-mail are not offered (SIM-swap protection).
    final boolean totpActive =
        settings.isChannelAllowed(StepUpChannel.TOTP) && totpService.hasActive(user.getId());
    final boolean onlyAuthenticator = totpActive && settings.totpExclusive();
    final List<String> channels =
        List.of(StepUpChannel.values()).stream()
            .filter(
                c ->
                    c == StepUpChannel.TOTP
                        ? totpActive
                        : !onlyAuthenticator && settings.isChannelAllowed(c) && contact.supports(c))
            .map(Enum::name)
            .toList();
    final StepUpConfigData data =
        new StepUpConfigData(
            settings.enabled(),
            java.util.stream.Stream.concat(
                    settings.protectedActions().stream(),
                    java.util.stream.Stream.of(StepUpAction.MANAGE_AUTHENTICATOR)
                        .filter(settings::isProtected))
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList(),
            channels,
            settings.otpLength(),
            settings.otpTtlSeconds(),
            settings.maxAttempts(),
            settings.resendCooldownSeconds(),
            settings.maxResends(),
            settings.grantTtlSeconds(),
            sessionResolver.sessionHash(httpRequest).isPresent());
    return GSON.toJson(data);
  }

  @POST
  @Path("/challenges")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Request a step-up challenge",
      description =
          "Sends a one-time code over the chosen channel (SMS or EMAIL), or, for TOTP, opens a"
              + " challenge to be answered with the code of the user's authenticator app (nothing"
              + " is sent). Any previous open challenge of the user for the same action is"
              + " replaced. Rate limited per user.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation = StepUpApiResourceSwagger.PostStepUpChallengeRequest.class)))
  @ApiResponse(
      responseCode = "201",
      description = "Challenge created",
      content =
          @Content(
              schema =
                  @Schema(implementation = StepUpApiResourceSwagger.StepUpChallengeResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Validation error / step-up not enabled for the action / channel unavailable")
  @ApiResponse(
      responseCode = "403",
      description = "No session token (error.msg.selfservice.stepup.session.required)")
  @ApiResponse(responseCode = "429", description = "Locked or rate limited (see Retry-After)")
  public Response createChallenge(
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    final String sessionHash =
        sessionResolver.sessionHash(httpRequest).orElseThrow(StepUpException::sessionRequired);
    final StepUpDataValidator.CreateChallengeRequest request =
        dataValidator.validateCreateChallenge(apiRequestBodyAsJson);

    final StepUpChallengeData data =
        challengeService.createChallenge(
            StepUpContact.from(user),
            sessionHash,
            request.action(),
            request.channel(),
            request.payloadValues(),
            StepUpRequestSupport.locale(httpRequest),
            StepUpRequestSupport.clientIp(httpRequest));
    return Response.status(Response.Status.CREATED)
        .type(MediaType.APPLICATION_JSON)
        .entity(GSON.toJson(data))
        .build();
  }

  @POST
  @Path("/challenges/{challengeId}/verify")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Verify the one-time code",
      description =
          "On success returns a one-time stepUpToken bound to the user, the session, the action and"
              + " the payload of the challenge. Send 'otp', or on a TOTP challenge a single-use"
              + " 'recoveryCode'. A wrong code consumes an attempt; after the configured number of"
              + " attempts the challenge is locked.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(implementation = StepUpApiResourceSwagger.PostStepUpVerifyRequest.class)))
  @ApiResponse(
      responseCode = "200",
      description = "Verified",
      content =
          @Content(
              schema =
                  @Schema(implementation = StepUpApiResourceSwagger.StepUpGrantResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "error.msg.selfservice.stepup.otp.invalid (attemptsRemaining) / otp.expired")
  @ApiResponse(
      responseCode = "403",
      description = "error.msg.selfservice.stepup.invalid (unknown, foreign or finished challenge)")
  @ApiResponse(responseCode = "429", description = "error.msg.selfservice.stepup.locked")
  public String verify(
      @PathParam("challengeId") final Long challengeId,
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    final String sessionHash =
        sessionResolver.sessionHash(httpRequest).orElseThrow(StepUpException::sessionRequired);
    final StepUpDataValidator.VerifyRequest request =
        dataValidator.validateVerify(apiRequestBodyAsJson);

    final StepUpGrantData grant =
        challengeService.verify(
            challengeId,
            request.otp(),
            request.recoveryCode(),
            user.getId(),
            sessionHash,
            StepUpRequestSupport.clientIp(httpRequest));
    return GSON.toJson(grant);
  }

  @POST
  @Path("/challenges/{challengeId}/resend")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Resend the one-time code",
      description =
          "Sends a new code for a still pending challenge. Subject to a cooldown and a maximum"
              + " number of resends; failed attempts are not reset.")
  @ApiResponse(
      responseCode = "200",
      description = "Resent",
      content =
          @Content(
              schema =
                  @Schema(implementation = StepUpApiResourceSwagger.StepUpChallengeResponse.class)))
  @ApiResponse(
      responseCode = "429",
      description = "error.msg.selfservice.stepup.resend.cooldown / resend.limit")
  public String resend(
      @PathParam("challengeId") final Long challengeId,
      @Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    final String sessionHash =
        sessionResolver.sessionHash(httpRequest).orElseThrow(StepUpException::sessionRequired);

    final StepUpChallengeData data =
        challengeService.resend(
            challengeId,
            StepUpContact.from(user),
            sessionHash,
            StepUpRequestSupport.locale(httpRequest),
            StepUpRequestSupport.clientIp(httpRequest));
    return GSON.toJson(data);
  }
}
