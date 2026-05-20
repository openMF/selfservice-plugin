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
package org.apache.fineract.selfservice.spm.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.selfservice.client.service.AppSelfServiceUserClientMapperReadService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.spm.api.ScorecardApiResource;
import org.apache.fineract.spm.data.ScorecardData;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Path("/v1/self/surveys/scorecards")
@Component
@Tag(name = "Self Score Card", description = "")
@RequiredArgsConstructor
public class SelfScorecardApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final ScorecardApiResource scorecardApiResource;
  private final AppSelfServiceUserClientMapperReadService appuserClientMapperReadService;

  @GET
  @Path("clients/{clientId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Transactional
  @Operation(
      summary = "List Scorecards for Client",
      description = "Retrieves all scorecards associated with the given client.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Not Found")
  })
  public List<ScorecardData> findByClient(@PathParam("clientId") final Long clientId) {

    validateAppSelfServiceUserClientsMapping(clientId);
    return this.scorecardApiResource.findByClient(clientId);
  }

  @POST
  @Path("{surveyId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Transactional
  @Operation(
      summary = "Create a Scorecard",
      description = "Submits a scorecard entry for the specified survey.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Not Found")
  })
  public void createScorecard(
      @PathParam("surveyId") final Long surveyId, final ScorecardData scorecardData) {
    if (scorecardData.getClientId() != null) {
      validateAppSelfServiceUserClientsMapping(scorecardData.getClientId());
      this.scorecardApiResource.createScorecard(surveyId, scorecardData);
    }
  }

  private void validateAppSelfServiceUserClientsMapping(final Long clientId) {
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    final boolean mappedClientId =
        this.appuserClientMapperReadService.isClientMappedToSelfServiceUser(clientId, user.getId());
    if (!mappedClientId) {
      throw new ClientNotFoundException(clientId);
    }
  }
}
