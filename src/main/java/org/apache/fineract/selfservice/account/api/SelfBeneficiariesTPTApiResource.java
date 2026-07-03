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
package org.apache.fineract.selfservice.account.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransferEnumerations;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.SelfBeneficiariesTPTData;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTReadPlatformService;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTWritePlatformService;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Path("/v1/self/beneficiaries/tpt")
@Component
@Tag(name = "Self Third Party Transfer", description = "")
@RequiredArgsConstructor
@Slf4j
public class SelfBeneficiariesTPTApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final DefaultToApiJsonSerializer<SelfBeneficiariesTPTData> toApiJsonSerializer;
  private final ApiRequestParameterHelper apiRequestParameterHelper;
  private final SelfBeneficiariesTPTReadPlatformService readPlatformService;
  private final SelfBeneficiariesTPTWritePlatformService writePlatformService;

  // Dependencies for asynchronous notifications
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

  private static final Set<String> RESPONSE_DATA_PARAMETERS =
      Set.of(
          SelfBeneficiariesTPTApiConstants.NAME_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.OFFICE_NAME_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.ACCOUNT_NUMBER_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.ACCOUNT_TYPE_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.TRANSFER_LIMIT_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.ID_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.CLIENT_NAME_PARAM_NAME,
          SelfBeneficiariesTPTApiConstants.ACCOUNT_TYPE_OPTIONS_PARAM_NAME);

  private static final String RESOURCE_NAME_FOR_PERMISSIONS =
      SelfBeneficiariesTPTApiConstants.BENEFICIARY_ENTITY_NAME;

  @GET
  @Path("template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Beneficiary Third Party Transfer Template",
      description = "Returns Account Type enumerations...")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfBeneficiariesTPTApiResourceSwagger
                                .GetSelfBeneficiariesTPTTemplateResponse.class)))
  })
  public String template(@Context final UriInfo uriInfo) {
    context.authenticatedSelfServiceUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final EnumOptionData loanAccountType =
        AccountTransferEnumerations.accountType(PortfolioAccountType.LOAN);
    final EnumOptionData savingsAccountType =
        AccountTransferEnumerations.accountType(PortfolioAccountType.SAVINGS);
    final Collection<EnumOptionData> accountTypeOptions =
        Arrays.asList(savingsAccountType, loanAccountType);

    SelfBeneficiariesTPTData templateData = new SelfBeneficiariesTPTData(accountTypeOptions);
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    return this.toApiJsonSerializer.serialize(settings, templateData, RESPONSE_DATA_PARAMETERS);
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Add TPT Beneficiary",
      description = "Creates a new third-party transfer beneficiary.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfBeneficiariesTPTApiResourceSwagger.PostSelfBeneficiariesTPTRequest
                              .class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfBeneficiariesTPTApiResourceSwagger.PostSelfBeneficiariesTPTResponse
                                .class)))
  })
  public String add(
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    context
        .authenticatedSelfServiceUser()
        .validateHasCreatePermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final JsonCommand command = JsonCommand.from(apiRequestBodyAsJson); // Simple & compatible
    final CommandProcessingResult result = this.writePlatformService.add(command);

    Map<String, Object> contextData = extractBeneficiaryContextForAdd(apiRequestBodyAsJson);
    publishBeneficiaryEvent(
        SelfServiceNotificationEvent.Type.BENEFICIARY_ADDED,
        result.getResourceId(),
        contextData,
        httpRequest);

    return this.toApiJsonSerializer.serialize(result);
  }

  @PUT
  @Path("{beneficiaryId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update TPT Beneficiary",
      description = "Updates an existing third-party transfer beneficiary.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfBeneficiariesTPTApiResourceSwagger
                              .PutSelfBeneficiariesTPTBeneficiaryIdRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfBeneficiariesTPTApiResourceSwagger
                                .PutSelfBeneficiariesTPTBeneficiaryIdResponse.class)))
  })
  public String update(
      @PathParam("beneficiaryId") @Parameter(description = "beneficiaryId")
          final Long beneficiaryId,
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    context
        .authenticatedSelfServiceUser()
        .validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final JsonCommand command = JsonCommand.from(apiRequestBodyAsJson); // With entityId
    final CommandProcessingResult result = this.writePlatformService.update(command);

    Map<String, Object> contextData =
        extractBeneficiaryContextForUpdate(apiRequestBodyAsJson, beneficiaryId);
    publishBeneficiaryEvent(
        SelfServiceNotificationEvent.Type.BENEFICIARY_UPDATED,
        beneficiaryId,
        contextData,
        httpRequest);

    return this.toApiJsonSerializer.serialize(result);
  }

  @DELETE
  @Path("{beneficiaryId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Delete TPT Beneficiary",
      description = "Soft-deletes an existing third-party transfer beneficiary.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfBeneficiariesTPTApiResourceSwagger
                                .DeleteSelfBeneficiariesTPTBeneficiaryIdResponse.class)))
  })
  public String delete(
      @PathParam("beneficiaryId") final Long beneficiaryId,
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    context
        .authenticatedSelfServiceUser()
        .validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final JsonCommand command = JsonCommand.from(apiRequestBodyAsJson);
    final CommandProcessingResult result = this.writePlatformService.delete(command);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("beneficiaryId", beneficiaryId);

    publishBeneficiaryEvent(
        SelfServiceNotificationEvent.Type.BENEFICIARY_DELETED,
        beneficiaryId,
        contextData,
        httpRequest);

    return this.toApiJsonSerializer.serialize(result);
  }

  @GET
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Get All TPT Beneficiaries",
      description = "Api to get all third party beneficiary...")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                array =
                    @ArraySchema(
                        schema =
                            @Schema(
                                implementation =
                                    SelfBeneficiariesTPTApiResourceSwagger
                                        .GetSelfBeneficiariesTPTResponse.class))))
  })
  public String retrieveAll(@Context final UriInfo uriInfo) {
    context.authenticatedSelfServiceUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

    final Collection<SelfBeneficiariesTPTData> beneficiaries =
        this.readPlatformService.retrieveAll();
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    return this.toApiJsonSerializer.serialize(settings, beneficiaries, RESPONSE_DATA_PARAMETERS);
  }

  // === Notification Helpers (unchanged) ===

  private Map<String, Object> extractBeneficiaryContextForAdd(String apiRequestBodyAsJson) {
    Map<String, Object> contextData = new HashMap<>();
    try {
      JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
      contextData.put("beneficiaryName", json.has("name") ? json.get("name").getAsString() : "");
      contextData.put(
          "accountNumber",
          json.has("accountNumber") ? json.get("accountNumber").getAsString() : "");
      contextData.put(
          "officeName", json.has("officeName") ? json.get("officeName").getAsString() : "");
      contextData.put(
          "accountType", json.has("accountType") ? json.get("accountType").getAsInt() : "");
    } catch (Exception e) {
      log.warn("Failed to parse beneficiary JSON for notification", e);
    }
    return contextData;
  }

  private Map<String, Object> extractBeneficiaryContextForUpdate(
      String apiRequestBodyAsJson, Long beneficiaryId) {
    Map<String, Object> contextData = new HashMap<>();
    try {
      JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
      contextData.put("beneficiaryName", json.has("name") ? json.get("name").getAsString() : "");
      contextData.put(
          "transferLimit",
          json.has("transferLimit") ? json.get("transferLimit").getAsBigDecimal() : "");
    } catch (Exception e) {
      log.warn("Failed to parse beneficiary JSON for notification", e);
    }
    contextData.put("beneficiaryId", beneficiaryId);
    return contextData;
  }

  private void publishBeneficiaryEvent(
      SelfServiceNotificationEvent.Type type,
      Long beneficiaryId,
      Map<String, Object> contextData,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);

      applicationEventPublisher.publishEvent(
          SelfServiceNotificationEvent.withTenantContext(
              this,
              type,
              user.getId(),
              user.getFirstname(),
              user.getLastname(),
              user.getUsername(),
              user.getEmail(),
              mobileNumber,
              emailMode,
              extractClientIp(httpRequest),
              LocaleContextHolder.getLocale(),
              contextData));
    } catch (Exception e) {
      log.warn("Failed to publish {} notification event", type, e);
    }
  }

  private String extractMobile(AppSelfServiceUser user) {
    if (user == null || user.getAppUserClientMappings() == null) return null;
    return user.getAppUserClientMappings().stream()
        .map(AppSelfServiceUserClientMapping::getClient)
        .filter(Objects::nonNull)
        .map(Client::getMobileNo)
        .filter(StringUtils::isNotBlank)
        .findFirst()
        .orElse(null);
  }

  private boolean determineMode(String email, String mobileNumber) {
    boolean hasEmail = StringUtils.isNotBlank(email);
    boolean hasMobile = StringUtils.isNotBlank(mobileNumber);
    if (hasEmail && !hasMobile) return true;
    if (hasMobile && !hasEmail) return false;
    String pref =
        env.getProperty("fineract.selfservice.notification.login.delivery-preference", "email");
    return "email".equalsIgnoreCase(pref);
  }

  private String extractClientIp(HttpServletRequest httpRequest) {
    if (httpRequest == null) return null;
    String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
    if (StringUtils.isNotBlank(xForwardedFor)) {
      String firstToken = xForwardedFor.split(",")[0].trim();
      if (StringUtils.isNotBlank(firstToken)) return firstToken;
    }
    return httpRequest.getRemoteAddr();
  }
}
