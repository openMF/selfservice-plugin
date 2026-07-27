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
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.ResendOtpRequest;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.service.AccountTransferQuoteService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferReadService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferWritePlatformService;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTReadPlatformService;
import org.apache.fineract.selfservice.account.service.SinpeExternalApiClient;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Path("/v1/self/accounttransfers")
@Component
@Tag(
    name = "Self Account transfer",
    description =
        "Endpoints for 3-step account transfers (Prepare, Quote, Confirm) and legacy account transfers")
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SelfAccountTransferWritePlatformService transferWritePlatformService;
  private final AccountTransferQuoteService quoteService;
  private final SinpeExternalApiClient sinpeExternalApiClient;
  private final SelfServiceRegistrationRepository registrationRepository;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

  private final DefaultToApiJsonSerializer<SelfAccountTransferData> toApiJsonSerializer;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  private final SelfAccountTransferReadService selfAccountTransferReadService;
  private final ApiRequestParameterHelper apiRequestParameterHelper;
  private final SelfAccountTransferDataValidator dataValidator;
  private final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  private final ConfigurationDomainService configurationDomainService;
  private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;

  @POST
  @Path("/prepare")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Prepare Transfer",
      description = "Validates and prepares the transfer details.")
  public String prepare(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    AccountTransferPrepareRequest request =
        new Gson().fromJson(apiRequestBodyAsJson, AccountTransferPrepareRequest.class);
    Object result = transferWritePlatformService.prepareTransfer(request);
    return result instanceof String ? (String) result : new Gson().toJson(result);
  }

  @POST
  @Path("/quote")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Quote Transfer",
      description = "Calculates the transfer fee based on business rules.")
  public String quote(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    AccountTransferPrepareRequest request =
        new Gson().fromJson(apiRequestBodyAsJson, AccountTransferPrepareRequest.class);
    Object result = transferWritePlatformService.quoteTransfer(request);
    return result instanceof String ? (String) result : new Gson().toJson(result);
  }

  @POST
  @Path("/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Confirm Transfer",
      description = "Sends OTP or executes the transfer if OTP is valid.")
  public String confirm(
      final String apiRequestBodyAsJson, @Context HttpServletRequest httpRequest) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    AccountTransferConfirmRequest request =
        new Gson().fromJson(apiRequestBodyAsJson, AccountTransferConfirmRequest.class);
    Object result = transferWritePlatformService.confirmTransfer(request, httpRequest);
    return result instanceof String ? (String) result : new Gson().toJson(result);
  }

  @GET
  @Path("template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Account Transfer Template",
      description =
          "Returns list of loan/savings accounts that can be used for account transfer\n\nExample Requests:\n\nself/accounttransfers/template\n")
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
                                    SelfAccountTransferApiResourceSwagger
                                        .GetAccountTransferTemplateResponse.class))))
  })
  public String template(
      @DefaultValue("") @QueryParam("type") @Parameter(name = "type") final String type,
      @Context final UriInfo uriInfo) {

    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());

    Collection<SelfAccountTemplateData> selfTemplateData =
        this.selfAccountTransferReadService.retrieveSelfAccountTemplateData(user);

    if ("tpt".equals(type)) {
      Collection<SelfAccountTemplateData> tptTemplateData =
          this.tptBeneficiaryReadPlatformService.retrieveTPTSelfAccountTemplateData(user);
      return this.toApiJsonSerializer.serialize(
          settings, new SelfAccountTransferData(selfTemplateData, tptTemplateData));
    }

    return this.toApiJsonSerializer.serialize(
        settings, new SelfAccountTransferData(selfTemplateData, selfTemplateData));
  }

  @POST
  @Path("/otp/resend")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Resend OTP for Transfer Confirmation",
      description =
          "Resends OTP for pending transfer confirmation. Regenerates new OTP and expires previous if remaining time < 50% of total expiry.")
  public String resendOtp(
      final String apiRequestBodyAsJson, @Context HttpServletRequest httpRequest) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    ResendOtpRequest request = new Gson().fromJson(apiRequestBodyAsJson, ResendOtpRequest.class);
    Object result = transferWritePlatformService.resendTransferOtp(request, httpRequest);
    return result instanceof String ? (String) result : new Gson().toJson(result);
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create new Transfer",
      description =
          "Ability to create new transfer of monetary funds from one account to another.\n\nExample Requests:\n\nself/accounttransfers/\n")
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
                                    SelfAccountTransferApiResourceSwagger.PostNewTransferResponse
                                        .class))))
  })
  public String create(
      @DefaultValue("") @QueryParam("type") @Parameter(name = "type") final String type,
      final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    CommandProcessingResult result =
        transferWritePlatformService.createTransfer(type, apiRequestBodyAsJson, httpRequest);
    return toApiJsonSerializer.serialize(result);
  }

  // =====================================================================
  //  ENDPOINTS PARA CONSULTA DE DETALLE DE TRANSACCIÓN
  // =====================================================================

  @POST
  @Path("/{accountId}/details")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
          summary = "Retrieve Transaction Details",
          description = "Queries details of a specific transaction by account ID and transaction/reference ID")
  @ApiResponses({
          @ApiResponse(
                  responseCode = "200",
                  description = "OK",
                  content = @Content(schema = @Schema(implementation = String.class)))
  })
  public String retrieveTransactionDetails(
          @PathParam("accountId") final Long accountId,
          final String apiRequestBodyAsJson) {

    com.google.gson.JsonObject jsonObject =
            new Gson().fromJson(apiRequestBodyAsJson, com.google.gson.JsonObject.class);

    String txnId = jsonObject.has("txnId") && !jsonObject.get("txnId").isJsonNull()
            ? jsonObject.get("txnId").getAsString() : null;
    String transferType = jsonObject.has("transferType") && !jsonObject.get("transferType").isJsonNull()
            ? jsonObject.get("transferType").getAsString() : "SAME_BANK";

    if (txnId == null || txnId.isBlank()) {
      throw new IllegalArgumentException("The 'txnId' parameter is required.");
    }

    Map<String, Object> details =
            this.selfAccountTransferReadService.retrieveTransactionDetails(accountId, txnId, transferType);

    return this.toApiJsonSerializer.serialize(details);
  }

  @GET
  @Path("/{accountId}/details/{txnId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
          summary = "Retrieve Transaction Details via GET",
          description = "Queries details of a specific transaction passing account ID, transaction ID, and transferType as query param")
  @ApiResponses({
          @ApiResponse(
                  responseCode = "200",
                  description = "OK",
                  content = @Content(schema = @Schema(implementation = String.class)))
  })
  public String retrieveTransactionDetailsGet(
          @PathParam("accountId") final Long accountId,
          @PathParam("txnId") final String txnId,
          @DefaultValue("SAME_BANK") @QueryParam("transferType") final String transferType) {

    if (txnId == null || txnId.isBlank()) {
      throw new IllegalArgumentException("The 'txnId' path parameter is required.");
    }

    Map<String, Object> details =
            this.selfAccountTransferReadService.retrieveTransactionDetails(accountId, txnId, transferType);

    return this.toApiJsonSerializer.serialize(details);
  }


}
