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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.service.StepUpGuard;
import org.apache.fineract.selfservice.stepup.service.StepUpPayloadHasher;
import org.apache.fineract.selfservice.stepup.service.StepUpRequestSupport;
import org.apache.fineract.selfservice.stepup.totp.TotpService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

/**
 * Pairing and managing the authenticator app (TOTP) of the logged-in self-service user.
 *
 * <p>Flow: {@code POST /enrollment} (a secret is returned once) → the user adds it to the app → a
 * step-up for {@code MANAGE_AUTHENTICATOR} → {@code POST /enrollment/confirm} with a code of the
 * app (and {@code X-Step-Up-Token}) → recovery codes are returned once. Afterwards the app is a
 * channel ({@code TOTP}) of the normal step-up challenges.
 *
 * <p>Activating, re-issuing recovery codes and removing an active authenticator are step-up
 * protected ({@link StepUpAction#MANAGE_AUTHENTICATOR}), so a stolen session cannot pair its own
 * phone. Nothing here ever returns a secret or recovery code a second time.
 */
@Path("/v1/self/stepup/totp")
@Component
@Tag(
    name = "Self Authenticator App",
    description =
        "Pair an authenticator app (TOTP) as a step-up channel. Off by default; a tenant switches"
            + " it on with totpEnabled in the SELF_SERVICE_STEP_UP external service.")
@RequiredArgsConstructor
public class SelfTotpApiResource {

  private static final Gson GSON = new Gson();

  private final PlatformSelfServiceSecurityContext context;
  private final TotpService totpService;
  private final StepUpDataValidator dataValidator;
  private final StepUpGuard stepUpGuard;

  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Authenticator status of the current user",
      description =
          "NONE, PENDING (secret shown, not confirmed) or ACTIVE, how many recovery codes are left"
              + " and whether the tenant offers the feature at all.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String status() {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    return GSON.toJson(totpService.status(user.getId()));
  }

  @POST
  @Path("/enrollment")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Start pairing an authenticator app",
      description =
          "Creates a pending secret and returns it once, as an otpauth:// URI (for a QR code) and"
              + " as text. A pending secret of the user is replaced. Refused while an authenticator"
              + " is active: remove it first.")
  @ApiResponse(responseCode = "201", description = "Secret created")
  @ApiResponse(responseCode = "400", description = "error.msg.selfservice.stepup.totp.disabled")
  @ApiResponse(
      responseCode = "409",
      description = "error.msg.selfservice.stepup.totp.already.enrolled")
  public Response beginEnrollment() {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    return Response.status(Response.Status.CREATED)
        .type(MediaType.APPLICATION_JSON)
        .entity(GSON.toJson(totpService.beginEnrollment(user.getId(), user.getUsername())))
        .build();
  }

  @POST
  @Path("/enrollment/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Confirm the authenticator app",
      description =
          "The code shown by the app proves it holds the secret. Needs a step-up grant for"
              + " MANAGE_AUTHENTICATOR (X-Step-Up-Token) when step-up protects that action."
              + " Returns the recovery codes once.")
  @ApiResponse(responseCode = "200", description = "Authenticator active, recovery codes returned")
  @ApiResponse(responseCode = "400", description = "error.msg.selfservice.stepup.otp.invalid")
  @ApiResponse(responseCode = "403", description = "Step-up required or invalid")
  @ApiResponse(responseCode = "429", description = "Locked after too many wrong codes")
  public String confirmEnrollment(
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    final String code = dataValidator.validateConfirmTotp(apiRequestBodyAsJson);
    return GSON.toJson(
        totpService.confirmEnrollment(
            user.getId(),
            code,
            StepUpRequestSupport.clientIp(httpRequest),
            () -> requireStepUp(httpRequest)));
  }

  @POST
  @Path("/recovery-codes")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Replace the recovery codes",
      description =
          "Returns a new set of recovery codes once; the old ones stop working. Needs a step-up"
              + " grant for MANAGE_AUTHENTICATOR when step-up protects that action.")
  @ApiResponse(responseCode = "200", description = "New recovery codes")
  @ApiResponse(responseCode = "400", description = "error.msg.selfservice.stepup.totp.not.enrolled")
  public String regenerateRecoveryCodes(@Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    return GSON.toJson(
        totpService.regenerateRecoveryCodes(
            user.getId(),
            StepUpRequestSupport.clientIp(httpRequest),
            () -> requireStepUp(httpRequest)));
  }

  @DELETE
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Remove the authenticator app",
      description =
          "Deletes the authenticator and its recovery codes. Needs a step-up grant for"
              + " MANAGE_AUTHENTICATOR when it is active and step-up protects that action;"
              + " cancelling a pairing that was never confirmed needs none.")
  @ApiResponse(responseCode = "200", description = "Removed")
  public Response remove(@Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    totpService.remove(
        user.getId(), StepUpRequestSupport.clientIp(httpRequest), () -> requireStepUp(httpRequest));
    return Response.ok(GSON.toJson(Map.of("removed", true)), MediaType.APPLICATION_JSON).build();
  }

  private void requireStepUp(final HttpServletRequest httpRequest) {
    stepUpGuard.requireIfProtected(
        StepUpAction.MANAGE_AUTHENTICATOR,
        httpRequest,
        () -> StepUpPayloadHasher.hash(StepUpAction.MANAGE_AUTHENTICATOR, Map.of()));
  }
}
