/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.domain.CompleteOnboardingUpToRequest;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.domain.UpdateOnboardingStepRequest;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.springframework.stereotype.Component;

/**
 * Self-service onboarding step progress API.
 *
 * <p>Step definitions live in {@code m_selfservice_onboarding_step_def} (tenant DB). Per-user
 * progress is in {@code m_selfservice_onboarding_step}. Used by the mobile app to advance
 * enrollment steps 6–15 after registration/confirm have completed 1–5.
 */
@Path("/v1/onboarding")
@Component
@Tag(name = "Self Service Onboarding Steps", description = "Track and update enrollment step status")
@RequiredArgsConstructor
@Slf4j
public class OnboardingStepApiResource {

  private final SelfServiceOnboardingStepService onboardingStepService;
  private final PlatformSecurityContext context;
  private final ToApiJsonSerializer<OnboardingProgressData> toApiJsonSerializer;
  private final Gson gson = new Gson();
  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "ONBOARDING";

  @GET
  @Path("/clients/{clientId}/steps")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Get onboarding progress by clientId (backoffice)",
      description =
          "Resolves the self-service user mapped to the client and returns enrollment progress."
              + " Intended for staff / system integrations (e.g. after KYC webhook).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "404", description = "No self-service user mapped to client"),
    @ApiResponse(responseCode = "401", description = "Unauthorized")
  })
  public String getProgressByClientId(
      @PathParam("clientId") @Parameter(description = "Fineract client id") final Long clientId) {

    this.context
        .authenticatedUser()
        .validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final OnboardingProgressData progress =
        this.onboardingStepService.getOrInitProgressByClientId(clientId);
    return this.toApiJsonSerializer.serialize(progress);
  }

  @PUT
  @Path("/clients/{clientId}/steps")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update an onboarding step by clientId (backoffice)",
      description =
          "Updates one step for the self-service user mapped to the given client."
              + " Same body as the self-service PUT /steps endpoint.")
  @RequestBody(
      required = true,
      content =
          @Content(schema = @Schema(implementation = UpdateOnboardingStepRequest.class)))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK — full progress after update"),
    @ApiResponse(responseCode = "400", description = "Invalid stepCode or status"),
    @ApiResponse(responseCode = "404", description = "No self-service user mapped to client"),
    @ApiResponse(responseCode = "401", description = "Unauthorized")
  })
  public String updateStepByClientId(
      @PathParam("clientId") @Parameter(description = "Fineract client id") final Long clientId,
      @Parameter(hidden = true) final String apiRequestBodyAsJson) {

    this.context
        .authenticatedUser()
        .validateHasPermissionTo("UPDATE_" + RESOURCE_NAME_FOR_PERMISSIONS);

    final UpdateOnboardingStepRequest request =
        this.gson.fromJson(apiRequestBodyAsJson, UpdateOnboardingStepRequest.class);
    final OnboardingProgressData progress =
        this.onboardingStepService.updateStepByClientId(clientId, request);
    return this.toApiJsonSerializer.serialize(progress);
  }

  @POST
  @Path("/clients/{clientId}/steps/complete-up-to")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Complete onboarding steps up to a step code (backoffice)",
      description =
          "Marks all steps with order ≤ the target step as COMPLETED for the user mapped to"
              + " this client. Used after external KYC Approved (e.g. Didit webhook)."
              + " Default upToStepCode = REGISTRATION_COMPLETE.")
  @RequestBody(
      required = false,
      content =
          @Content(schema = @Schema(implementation = CompleteOnboardingUpToRequest.class)))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK — full progress after completion"),
    @ApiResponse(responseCode = "404", description = "No self-service user mapped to client"),
    @ApiResponse(responseCode = "401", description = "Unauthorized")
  })
  public String completeStepsUpToByClientId(
      @PathParam("clientId") @Parameter(description = "Fineract client id") final Long clientId,
      @QueryParam("upToStepCode") @DefaultValue("REGISTRATION_COMPLETE") final String upToStepCode,
      @Parameter(hidden = true) final String apiRequestBodyAsJson) {

    this.context
        .authenticatedUser()
        .validateHasPermissionTo("UPDATE_" + RESOURCE_NAME_FOR_PERMISSIONS);

    String stepCode = upToStepCode;
    if (apiRequestBodyAsJson != null && !apiRequestBodyAsJson.isBlank()) {
      final CompleteOnboardingUpToRequest body =
          this.gson.fromJson(apiRequestBodyAsJson, CompleteOnboardingUpToRequest.class);
      if (body != null && body.getUpToStepCode() != null && !body.getUpToStepCode().isBlank()) {
        stepCode = body.getUpToStepCode();
      }
    }

    final OnboardingProgressData progress =
        this.onboardingStepService.completeStepsUpToByClientId(clientId, stepCode);
    return this.toApiJsonSerializer.serialize(progress);
  }
}