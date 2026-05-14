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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.client.service.SelfServiceClientIdentityDataReadPlatformService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** JAX-RS resource exposing self-service registration and enrollment endpoints. */
@Path("/v1/self/identity")
@Component
@Tag(name = "Person Identity", description = "")
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "mifos.self.service.external.identity.system.enabled", // property name
    havingValue = "true", // enable when value is "true"
    matchIfMissing = false // disabled by default if property is missing
    )
public class SelfServiceIdentityResource {

  private final DefaultToApiJsonSerializer<AppSelfServiceUser> toApiJsonSerializer;
  private final SelfServiceClientIdentityDataReadPlatformService
      selfServiceClientIdentityDataReadPlatformService;

  @POST
  @Path("retrieve")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Identity Data",
      description = "Retrieve Identity Information from Third Party System.")
  @RequestBody(
      required = true,
      content =
          @Content(schema = @Schema(implementation = SelfServiceRetrieveIdentityRequest.class)))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "400", description = "Bad Request")
  })
  public String retrieveIdentity(
      final SelfServiceRetrieveIdentityRequest selfServiceRetrieveIdentityRequest)
      throws Exception {

    return this.toApiJsonSerializer.serialize(
        this.selfServiceClientIdentityDataReadPlatformService.retrieveClientIdentityData(
            selfServiceRetrieveIdentityRequest));
  }
}
