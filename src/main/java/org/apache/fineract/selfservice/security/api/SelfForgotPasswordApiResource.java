package org.apache.fineract.selfservice.security.api;

import io.swagger.v3.oas.annotations.Parameter;
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
  public String requestResetPassword(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    this.selfServiceForgotPassworWritePlatformService.createForgotPasswordRequest(
        apiRequestBodyAsJson);
    return SelfServiceApiConstants.createForgotPasswordRequestSuccessMessage;
  }

  @POST
  @Path("/renew")
  @Produces({MediaType.APPLICATION_JSON})
  public String renewPassword(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    CommandProcessingResult result =
        this.selfServiceForgotPassworWritePlatformService.renewPassword(apiRequestBodyAsJson);
    return this.toApiJsonSerializer.serialize(result);
  }
}
