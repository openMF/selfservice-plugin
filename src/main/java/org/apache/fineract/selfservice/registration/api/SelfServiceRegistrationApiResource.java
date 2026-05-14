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
package org.apache.fineract.selfservice.registration.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.client.service.SelfServiceClientIdentifierReadPlatformService;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.data.IdentityDocumentData;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationWritePlatformService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

/** JAX-RS resource exposing self-service registration and enrollment endpoints. */
@Path("/v1/self/registration")
@Component
@Tag(name = "Self Service Registration", description = "")
@RequiredArgsConstructor
public class SelfServiceRegistrationApiResource {

  private final SelfServiceRegistrationWritePlatformService
      selfServiceRegistrationWritePlatformService;
  private final DefaultToApiJsonSerializer<AppSelfServiceUser> toApiJsonSerializer;
  private final SelfServiceClientIdentifierReadPlatformService clientIdentifierReadPlatformService;

  /**
   * Creates a self-service registration request pending later confirmation.
   *
   * @param apiRequestBodyAsJson request payload as raw JSON
   * @return success message for request creation
   */
  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String createSelfServiceRegistrationRequest(final String apiRequestBodyAsJson) {
    this.selfServiceRegistrationWritePlatformService.createRegistrationRequest(
        apiRequestBodyAsJson);
    return SelfServiceApiConstants.createRequestSuccessMessage;
  }

  /**
   * Creates a self-service user from a confirmed registration request.
   *
   * @param apiRequestBodyAsJson request payload as raw JSON
   * @return serialized command result containing the created user identifier
   */
  @POST
  @Path("user")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String createSelfServiceUser(final String apiRequestBodyAsJson) {
    AppSelfServiceUser user =
        this.selfServiceRegistrationWritePlatformService.createSelfServiceUser(
            apiRequestBodyAsJson);
    return this.toApiJsonSerializer.serialize(CommandProcessingResult.resourceResult(user.getId()));
  }

  /**
   * Creates a client and linked disabled self-service user, then sends an enrollment confirmation
   * token. The user must confirm the token via {@code POST /self/registration/client-user/confirm}
   * to activate.
   *
   * @param apiRequestBodyAsJson request payload as raw JSON
   * @return success message indicating the enrollment request was created
   */
  @POST
  @Path("client-user")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Self Enrollment Flow",
      description =
          "Creates a Fineract Client and a disabled Self Service User. Returns a success message (token is sent via email/SMS).")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = SelfServiceEnrollmentRequest.class)))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "400", description = "Bad Request"),
    @ApiResponse(responseCode = "409", description = "Conflict (Duplicate Username, Email, etc)")
  })
  public String selfEnroll(final String apiRequestBodyAsJson) {
    this.selfServiceRegistrationWritePlatformService.selfEnroll(apiRequestBodyAsJson);
    return SelfServiceApiConstants.createRequestSuccessMessage;
  }

  /**
   * Confirms a self-enrollment token and activates the disabled user.
   *
   * @param apiRequestBodyAsJson request payload containing the enrollment confirmation token
   * @return serialized command result containing the activated user identifier
   */
  @POST
  @Path("client-user/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Confirm Self Enrollment",
      description = "Validates the enrollment token and activates the self-service user.")
  @RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = SelfServiceConfirmationRequest.class)))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "403", description = "Token expired or already consumed"),
    @ApiResponse(responseCode = "404", description = "Token not found")
  })
  public String confirmEnrollment(final String apiRequestBodyAsJson) {
    AppSelfServiceUser user =
        this.selfServiceRegistrationWritePlatformService.confirmEnrollment(apiRequestBodyAsJson);
    return this.toApiJsonSerializer.serialize(CommandProcessingResult.resourceResult(user.getId()));
  }

  @GET
  @Path("identifiers")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List all client identity documents",
      description = "Returns all the available client identity documents supported")
  public List<IdentityDocumentData> retrieveAllClientIdentifiers() {
    return this.clientIdentifierReadPlatformService.retrieveClientIdentifiers();
  }
}
