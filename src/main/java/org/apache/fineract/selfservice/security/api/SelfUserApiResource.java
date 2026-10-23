/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.security.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.activity.domain.ActivityOutcome;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.service.ActivityLogService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService;
import org.apache.fineract.selfservice.security.service.SelfUserPasswordChangeService;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.service.StepUpGuard;
import org.apache.fineract.selfservice.stepup.service.StepUpPayloadHasher;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

@Path("/v1/self/user")
@Component
@Tag(name = "Self User", description = "")
@RequiredArgsConstructor
public class SelfUserApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SelfUserPasswordChangeService passwordChangeService;
  private final StepUpGuard stepUpGuard;
  private final StepUpSettingsProvider stepUpSettingsProvider;
  private final SelfServiceAuthenticationTokenService tokenService;
  private final ActivityLogService activityLogService;

  @PUT
  @Operation(
      summary = "Update User",
      description =
          "This API can be used by Self Service user to update their own user information."
              + " Currently, \"password\" and \"repeatPassword\" are the only parameters accepted.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(implementation = SelfUserApiResourceSwagger.PutSelfUserRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(implementation = SelfUserApiResourceSwagger.PutSelfUserResponse.class)))
  })
  public String update(
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context final HttpServletRequest httpRequest) {
    final AppSelfServiceUser appUser = this.context.authenticatedSelfServiceUser();

    // A malformed request must not use up a one-time step-up token.
    this.passwordChangeService.validateRequest(apiRequestBodyAsJson);

    // MX-488: a password change needs a fresh step-up verification when the tenant enabled it.
    // The token is bound to the action only: the password is never hashed, stored or logged.
    this.stepUpGuard.requireIfProtected(
        StepUpAction.CHANGE_PASSWORD,
        httpRequest,
        () -> StepUpPayloadHasher.hash(StepUpAction.CHANGE_PASSWORD, java.util.Map.of()));

    final String response =
        this.passwordChangeService.changePassword(appUser, apiRequestBodyAsJson);
    this.activityLogService.record(
        ActivityType.PASSWORD_CHANGED,
        ActivityOutcome.SUCCESS,
        appUser.getId(),
        null,
        null);

    // US-E01-03: the password change forces a re-login. All sessions of the user are revoked so a
    // stolen session cannot outlive the change.
    if (this.stepUpSettingsProvider.current().invalidateSessionsOnPasswordChange()) {
      this.tokenService.invalidateAllForUser(appUser.getId());
    }
    return response;
  }
}
