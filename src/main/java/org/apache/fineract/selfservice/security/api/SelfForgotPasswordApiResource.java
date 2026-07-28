/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.service.SelfServiceForgotPasswordWritePlatformService;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/self/password")
@Tag(
    name = "Self Service Password Forgot",
    description =
        "Endpoints for requesting and renewing self-service user passwords. The reset token is "
            + "automatically delivered through the notification channels enabled in the system "
            + "(Email, SMS, WhatsApp, In-App).")
@RequiredArgsConstructor
public class SelfForgotPasswordApiResource {

  private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
  private final SelfServiceForgotPasswordWritePlatformService
      selfServiceForgotPassworWritePlatformService;

  /**
   * Initiates a password reset request for the self-service user. Only the {@code username} is
   * required in the request body. The system will automatically determine the best notification
   * channel(s) based on the user's profile and the channels enabled in the system configuration.
   *
   * <p>Example request body:
   *
   * <pre>{@code
   * {
   *   "username": "vilma"
   * }
   * }</pre>
   *
   * @param apiRequestBodyAsJson JSON payload containing the {@code username} field
   * @return a success message indicating that the reset instructions have been sent
   */
  @POST
  @Path("/request")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Request Password Reset",
      description =
          "Initiates a password reset request for the self-service user. Only the 'username' field"
              + " is required. The reset token is automatically delivered through the notification"
              + " channels enabled in the system (Email, SMS, WhatsApp, In-App).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK - Reset instructions sent"),
    @ApiResponse(
        responseCode = "400",
        description = "Bad Request - Username is missing or invalid"),
    @ApiResponse(responseCode = "404", description = "Not Found - Username does not exist")
  })
  public String requestResetPassword(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    this.selfServiceForgotPassworWritePlatformService.createForgotPasswordRequest(
        apiRequestBodyAsJson);
    return SelfServiceApiConstants.createForgotPasswordRequestSuccessMessage;
  }

  /**
   * Renews the password using a valid reset token that was previously sent to the user.
   *
   * @param apiRequestBodyAsJson JSON payload containing the reset token and the new password
   * @return the command processing result
   */
  @POST
  @Path("/renew")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Renew Password",
      description = "Renews the password using a valid reset token.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK - Password renewed successfully"),
    @ApiResponse(responseCode = "400", description = "Bad Request - Token is invalid or expired")
  })
  public String renewPassword(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    CommandProcessingResult result =
        this.selfServiceForgotPassworWritePlatformService.renewPassword(apiRequestBodyAsJson);
    return this.toApiJsonSerializer.serialize(result);
  }
}
