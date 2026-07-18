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
package org.apache.fineract.selfservice.pockets.api;

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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.commands.service.CommandWrapperBuilderSelfService;
import org.apache.fineract.selfservice.pockets.data.PocketAccountMappingData;
import org.apache.fineract.selfservice.pockets.service.PocketAccountMappingReadPlatformService;
import org.springframework.stereotype.Component;

/**
 * JAX-RS resource exposing self-service pocket endpoints under {@code /v1/self/pockets}.
 *
 * <p>Pockets behave as favourites: an authenticated self-service user can link their own loan,
 * savings and share accounts to a pocket for faster access, and delink them later.
 */
@Path("/v1/self/pockets")
@Component
@Tag(
    name = "Pocket",
    description =
        "Pockets behave as favourites. A self-service user can link their Loan, Savings and Share accounts to a pocket for faster access, and delink them later.")
@RequiredArgsConstructor
public class PocketApiResource {

  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  private final DefaultToApiJsonSerializer<PocketAccountMappingData> toApiJsonSerializer;
  private final PocketAccountMappingReadPlatformService pocketAccountMappingReadPlatformService;

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Link/delink accounts to/from pocket",
      description =
          "Pockets behave as favourites. A user can link his/her Loan, Savings and Share accounts to a pocket for faster access. In a similar way linked accounts can be delinked from the pocket.\n"
              + "\n"
              + "Example Requests:\n"
              + "\n"
              + "self/pockets?command=linkAccounts\n"
              + "\n"
              + "self/pockets?command=delinkAccounts",
      requestBody =
          @RequestBody(
              content =
                  @Content(
                      schema =
                          @Schema(
                              implementation =
                                  PocketApiResourceSwagger.PostLinkDelinkAccountsToFromPocketRequest
                                      .class))))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            PocketApiResourceSwagger.PostLinkDelinkAccountsToFromPocketResponse
                                .class)))
  })
  public String handleCommands(
      @QueryParam("command") @Parameter(description = "command") final String commandParam,
      @Context final UriInfo uriInfo,
      @Parameter(hidden = true) final String apiRequestBodyAsJson) {

    CommandProcessingResult result = null;

    if (is(commandParam, PocketApiConstants.linkAccountsToPocketCommandParam)) {
      final CommandWrapper commandRequest =
          new CommandWrapperBuilderSelfService()
              .linkAccountsToPocket()
              .withJson(apiRequestBodyAsJson)
              .build();
      result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    } else if (is(commandParam, PocketApiConstants.delinkAccountsFromPocketCommandParam)) {
      final CommandWrapper commandRequest =
          new CommandWrapperBuilderSelfService()
              .delinkAccountsFromPocket()
              .withJson(apiRequestBodyAsJson)
              .build();
      result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    if (result == null) {
      throw new UnrecognizedQueryParamException("command", commandParam);
    }

    return this.toApiJsonSerializer.serialize(result);
  }

  @GET
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve accounts linked to pocket",
      description =
          "Returns the loan, savings and share accounts linked to the authenticated user's pocket.\n"
              + "\n"
              + "Example Requests:\n"
              + "\n"
              + "self/pockets")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            PocketApiResourceSwagger.GetAccountsLinkedToPocketResponse.class)))
  })
  public String retrieveAll() {
    return this.toApiJsonSerializer.serialize(
        this.pocketAccountMappingReadPlatformService.retrieveAll());
  }

  private boolean is(final String commandParam, final String commandValue) {
    return StringUtils.isNotBlank(commandParam)
        && commandParam.trim().equalsIgnoreCase(commandValue);
  }
}
