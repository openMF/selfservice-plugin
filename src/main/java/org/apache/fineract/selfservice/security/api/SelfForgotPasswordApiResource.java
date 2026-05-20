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
import org.apache.fineract.selfservice.registration.service.SelfServiceForgotPassworWritePlatformService;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/self/password")
@Tag(name = "Self Service Password Forgot for requesting and renewing", description = "")
@RequiredArgsConstructor
public class SelfForgotPasswordApiResource {

  private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
  private final SelfServiceForgotPassworWritePlatformService
      selfServiceForgotPassworWritePlatformService;

  @POST
  @Path("/request")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Request Password Reset",
      description = "Initiates a password reset request for the self-service user.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "400", description = "Bad Request")
  })
  public String requestResetPassword(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    this.selfServiceForgotPassworWritePlatformService.createForgotPasswordRequest(
        apiRequestBodyAsJson);
    return SelfServiceApiConstants.createForgotPasswordRequestSuccessMessage;
  }

  @POST
  @Path("/renew")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Renew Password",
      description = "Renews the password using a valid reset token.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "400", description = "Bad Request")
  })
  public String renewPassword(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    CommandProcessingResult result =
        this.selfServiceForgotPassworWritePlatformService.renewPassword(apiRequestBodyAsJson);
    return this.toApiJsonSerializer.serialize(result);
  }
}
