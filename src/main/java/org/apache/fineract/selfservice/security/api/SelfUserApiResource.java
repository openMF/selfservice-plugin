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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.security.service.SelfUserPasswordChangeService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

@Path("/v1/self/user")
@Component
@Tag(name = "Self User", description = "")
@RequiredArgsConstructor
public class SelfUserApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SelfUserPasswordChangeService passwordChangeService;

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
  public String update(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    final AppSelfServiceUser appUser = this.context.authenticatedSelfServiceUser();
    return this.passwordChangeService.changePassword(appUser, apiRequestBodyAsJson);
  }
}
