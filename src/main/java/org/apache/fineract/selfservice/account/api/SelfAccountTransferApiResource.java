/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.data.*;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferReadService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferWritePlatformService;
import org.apache.fineract.selfservice.account.service.SelfServiceExternalTransferService;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

@Path("/v1/self/accounttransfers")
@Component
@Tag(
    name = "Self Account transfer",
    description = "Endpoints for 3-step account transfers (Prepare, Quote, Confirm) supporting SINPE, PIN, and TPT payloads")
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SelfAccountTransferWritePlatformService transferWritePlatformService;  
  private final SelfServiceExternalTransferService externalTransferService;
  private final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  private final ApiRequestParameterHelper apiRequestParameterHelper;
  private final DefaultToApiJsonSerializer<SelfAccountTransferData> toApiJsonSerializer;
  private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonResultSerializer;
  private final Gson gson = new Gson();  
  private final SelfAccountTransferReadService selfAccountTransferReadService;
  

  @POST
  @Path("/prepare")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Prepare Transfer", description = "Validates and prepares the transfer details for SINPE, PIN, or TPT.")
  public String prepare(
      @QueryParam("type") @Parameter(description = "Transfer type: sinpe, pin, tpt") String type,
      final String apiRequestBodyAsJson) {
    
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    
    if ("sinpe".equalsIgnoreCase(type)) {
        SinpeTransferRequest request = gson.fromJson(apiRequestBodyAsJson, SinpeTransferRequest.class);
        // Add specific validation logic here if needed
        return gson.toJson(Map.of("status", "prepared", "type", "sinpe", "data", request));
    } else if ("pin".equalsIgnoreCase(type)) {
        PinTransferRequest request = gson.fromJson(apiRequestBodyAsJson, PinTransferRequest.class);
        return gson.toJson(Map.of("status", "prepared", "type", "pin", "data", request));
    } else if ("tpt".equalsIgnoreCase(type)) {
        TptTransferRequest request = gson.fromJson(apiRequestBodyAsJson, TptTransferRequest.class);
        return gson.toJson(Map.of("status", "prepared", "type", "tpt", "data", request));
    }
    
    throw new BadRequestException("Unsupported transfer type: " + type);
  }

  @POST
  @Path("/quote")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Quote Transfer", description = "Calculates the transfer fee based on business rules.")
  public String quote(
      @QueryParam("type") @Parameter(description = "Transfer type: sinpe, pin, tpt") String type,
      final String apiRequestBodyAsJson) {
    
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    
    // Integrate with your existing quoteService here based on the type
    // For demonstration, returning a mock quote structure
    return gson.toJson(Map.of("status", "quoted", "type", type, "fee", 0.00, "currency", "CRC"));
  }

  @POST
  @Path("/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Confirm Transfer", description = "Executes the transfer for SINPE, PIN, or TPT.")
  public String confirm(
      @QueryParam("type") @Parameter(description = "Transfer type: sinpe, pin, tpt") String type,
      final String apiRequestBodyAsJson, 
      @Context HttpServletRequest httpRequest) {
    
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    
    if ("sinpe".equalsIgnoreCase(type)) {
        SinpeTransferRequest request = gson.fromJson(apiRequestBodyAsJson, SinpeTransferRequest.class);
        return externalTransferService.executeSinpeTransfer(request);
    } else if ("pin".equalsIgnoreCase(type)) {
        PinTransferRequest request = gson.fromJson(apiRequestBodyAsJson, PinTransferRequest.class);
        return externalTransferService.executePinTransfer(request);
    } else if ("tpt".equalsIgnoreCase(type)) {
        TptTransferRequest request = gson.fromJson(apiRequestBodyAsJson, TptTransferRequest.class);
        // For TPT, you can route to internal Fineract service or proxy as needed
        return externalTransferService.executeTptTransfer(request);
    }
    
    throw new BadRequestException("Unsupported transfer type: " + type);
  }
 
  @GET
  @Path("template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Account Transfer Template",
      description = "Returns list of loan/savings accounts that can be used for account transfer\n\nExample Requests:\n\nself/accounttransfers/template\n")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = SelfAccountTransferApiResourceSwagger.GetAccountTransferTemplateResponse.class))))
  })
  public String template(
      @DefaultValue("") @QueryParam("type") @Parameter(name = "type") final String type,
      @Context final UriInfo uriInfo) {

    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    Collection<SelfAccountTemplateData> selfTemplateData = this.selfAccountTransferReadService.retrieveSelfAccountTemplateData(user);

    if (type.equals("tpt")) {
      Collection<SelfAccountTemplateData> tptTemplateData = this.tptBeneficiaryReadPlatformService.retrieveTPTSelfAccountTemplateData(user);
      return this.toApiJsonSerializer.serialize(settings, new SelfAccountTransferData(selfTemplateData, tptTemplateData));
    }

    return this.toApiJsonSerializer.serialize(settings, new SelfAccountTransferData(selfTemplateData, selfTemplateData));
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create new Transfer",
      description = "Ability to create new transfer of monetary funds from one account to another.\n\nExample Requests:\n\nself/accounttransfers/\n")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = SelfAccountTransferApiResourceSwagger.PostNewTransferResponse.class))))
  })
  public String create(
      @DefaultValue("") @QueryParam("type") @Parameter(name = "type") final String type,
      final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {
    
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    CommandProcessingResult result = transferWritePlatformService.createTransfer(type, apiRequestBodyAsJson, httpRequest);
    return toApiJsonResultSerializer.serialize(result);
  }
}