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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.domain.UpdateOnboardingStepRequest;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

/**
 * Self-service onboarding step progress API.
 *
 * <p>Step definitions live in {@code m_selfservice_onboarding_step_def} (tenant DB). Per-user
 * progress is in {@code m_selfservice_onboarding_step}. Used by the mobile app to advance
 * enrollment steps 6–15 after registration/confirm have completed 1–5.
 */
@Path("/v1/self/onboarding/steps")
@Component
@Tag(name = "Self Service Onboarding Steps", description = "Track and update enrollment step status")
@RequiredArgsConstructor
@Slf4j
public class SelfServiceOnboardingStepApiResource {

  private final SelfServiceOnboardingStepService onboardingStepService;
  private final PlatformSelfServiceSecurityContext context;
  private final ToApiJsonSerializer<OnboardingProgressData> toApiJsonSerializer;
  private final Gson gson = new Gson();

  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Get onboarding step progress",
      description =
          "Returns the authenticated self-service user's enrollment progress: current step,"
              + " completed count, percent, and full step list (from DB definitions).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "401", description = "Unauthorized")
  })
  public String getProgress() {
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    OnboardingProgressData progress =
        this.onboardingStepService.getOrInitProgress(user.getId());
    return this.toApiJsonSerializer.serialize(progress);
  }

  @PUT
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update an onboarding step",
      description =
          "Updates status of a single step for the authenticated user"
              + " (PENDING | IN_PROGRESS | COMPLETED | SKIPPED | FAILED)."
              + " Optional metadataJson may store vendor session, document type, file id, etc.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema = @Schema(implementation = UpdateOnboardingStepRequest.class),
              examples =
                  @io.swagger.v3.oas.annotations.media.ExampleObject(
                      value =
                          """
                          {
                            "stepCode": "DOCUMENT_UPLOAD",
                            "status": "COMPLETED",
                            "metadataJson": "{\\"documentType\\":\\"INE\\",\\"fileId\\":\\"abc\\"}"
                          }
                          """)))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK — full progress after update"),
    @ApiResponse(responseCode = "400", description = "Invalid stepCode or status"),
    @ApiResponse(responseCode = "401", description = "Unauthorized")
  })
  public String updateStep(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    UpdateOnboardingStepRequest request =
        this.gson.fromJson(apiRequestBodyAsJson, UpdateOnboardingStepRequest.class);
    OnboardingProgressData progress =
        this.onboardingStepService.updateStep(user.getId(), request);
    return this.toApiJsonSerializer.serialize(progress);
  }
}