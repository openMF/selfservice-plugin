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
package org.apache.fineract.selfservice.loanaccount.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.api.LoanChargesApiResource;
import org.apache.fineract.portfolio.loanaccount.api.LoanTransactionsApiResource;
import org.apache.fineract.portfolio.loanaccount.api.LoansApiResource;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTemplateTypeRequiredException;
import org.apache.fineract.portfolio.loanaccount.exception.NotSupportedLoanTemplateTypeException;
import org.apache.fineract.portfolio.loanaccount.guarantor.api.GuarantorsApiResource;
import org.apache.fineract.portfolio.loanaccount.guarantor.data.GuarantorData;
import org.apache.fineract.portfolio.loanaccount.guarantor.data.GuarantorsRequest;
import org.apache.fineract.selfservice.loanaccount.data.SelfLoansDataValidator;
import org.apache.fineract.selfservice.loanaccount.service.AppuserLoansMapperReadService;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Path("/v1/self/loans")
@Component
@Tag(name = "Self Loans", description = "")
@RequiredArgsConstructor
@Slf4j
public class SelfLoansApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final LoansApiResource loansApiResource;
  private final LoanTransactionsApiResource loanTransactionsApiResource;
  private final LoanChargesApiResource loanChargesApiResource;
  private final SelfServiceOwnershipGuard selfServiceOwnershipGuard;
  private final SelfLoansDataValidator dataValidator;
  private final GuarantorsApiResource guarantorsApiResource;

  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;
  
  private final NotificationDeliveryModeUtil notificationDeliveryModeUtil;
  private final LoanRepositoryWrapper loanRepositoryWrapper;

  @GET
  @Path("{loanId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a Loan",
      description =
          "Retrieves a Loan\n\n"
              + "Example Requests:\n"
              + "\n"
              + "self/loans/1\n"
              + "\n"
              + "\n"
              + "self/loans/1?fields=id,principal,annualInterestRate\n"
              + "\n"
              + "\n"
              + "self/loans/1?fields=id,principal,annualInterestRate&associations=repaymentSchedule,transactions")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfLoansApiResourceSwagger.GetSelfLoansLoanIdResponse.class)))
  })
  public String retrieveLoan(
      @PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
      @Context final UriInfo uriInfo) {

    this.dataValidator.validateRetrieveLoan(uriInfo);

    validateAppSelfServiceUserLoanMapping(loanId);

    final boolean staffInSelectedOfficeOnly = false;
    final String associations = LoanApiConstants.LOAN_ASSOCIATIONS_ALL;
    final String exclude = null;
    final String fields = null;
    return this.loansApiResource.retrieveLoan(
        loanId, staffInSelectedOfficeOnly, associations, exclude, fields, uriInfo);
  }

  @GET
  @Path("{loanId}/transactions/{transactionId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a Loan Transaction Details",
      description =
          "Retrieves a Loan Transaction Details"
              + "Example Request:\n"
              + "\n"
              + "self/loans/5/transactions/3")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfLoansApiResourceSwagger
                                .GetSelfLoansLoanIdTransactionsTransactionIdResponse.class)))
  })
  public String retrieveTransaction(
      @PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
      @PathParam("transactionId") @Parameter(description = "transactionId")
          final Long transactionId,
      @QueryParam("fields")
          @Parameter(
              in = ParameterIn.QUERY,
              name = "fields",
              description = "Optional Loan Transaction attribute list to be in the response",
              required = false,
              example = "id,date,amount")
          final String fields,
      @Context final UriInfo uriInfo) {

    this.dataValidator.validateRetrieveTransaction(uriInfo);

    validateAppSelfServiceUserLoanMapping(loanId);

    return this.loanTransactionsApiResource.retrieveTransaction(
        loanId, transactionId, fields, uriInfo);
  }

  @GET
  @Path("{loanId}/charges")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Loan Charges",
      description =
          "Lists loan Charges\n\n"
              + "Example Requests:\n"
              + "\n"
              + "self/loans/1/charges\n"
              + "\n"
              + "\n"
              + "self/loans/1/charges?fields=name,amountOrPercentage")
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
                                    SelfLoansApiResourceSwagger.GetSelfLoansLoanIdChargesResponse
                                        .class))))
  })
  public String retrieveAllLoanCharges(
      @PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
      @Context final UriInfo uriInfo) {

    validateAppSelfServiceUserLoanMapping(loanId);

    return this.loanChargesApiResource.retrieveAllLoanCharges(loanId, uriInfo);
  }

  @GET
  @Path("{loanId}/charges/{chargeId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a Loan Charge",
      description =
          "Retrieves a Loan Charge\n\n"
              + "Example Requests:\n"
              + "\n"
              + "self/loans/1/charges/1\n"
              + "\n"
              + "\n"
              + "self/loans/1/charges/1?fields=name,amountOrPercentage")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfLoansApiResourceSwagger.GetSelfLoansLoanIdChargesResponse.class)))
  })
  public String retrieveLoanCharge(
      @PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
      @PathParam("chargeId") @Parameter(description = "chargeId") final Long loanChargeId,
      @Context final UriInfo uriInfo) {

    validateAppSelfServiceUserLoanMapping(loanId);

    return this.loanChargesApiResource.retrieveLoanCharge(loanId, loanChargeId, uriInfo);
  }

  @GET
  @Path("template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Loan Details Template",
      description =
          "Retrieves Loan Details Template\n\n"
              + "This is a convenience resource. It can be useful when building maintenance user"
              + " interface screens for client applications. The template data returned consists of"
              + " any or all of:\n"
              + "\n"
              + "Field Defaults\n"
              + "Allowed description Lists\n\n"
              + "Example Requests:\n"
              + "\n"
              + "self/loans/template?templateType=individual&clientId=1\n"
              + "\n"
              + "\n"
              + "self/loans/template?templateType=individual&clientId=1&productId=1")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfLoansApiResourceSwagger.GetSelfLoansTemplateResponse.class)))
  })
  public String template(
      @QueryParam("clientId") @Parameter(description = "clientId") final Long clientId,
      @QueryParam("productId") @Parameter(description = "productId") final Long productId,
      @QueryParam("templateType") @Parameter(description = "templateType")
          final String templateType,
      @Context final UriInfo uriInfo) {

    if (clientId != null) {
      validateAppSelfServiceUserClientsMapping(clientId);
    }

    if (templateType == null) {
      final String errorMsg = "Loan template type must be provided";
      throw new LoanTemplateTypeRequiredException(errorMsg);
    } else if (!(templateType.equalsIgnoreCase("individual")
        || templateType.equalsIgnoreCase("collateral"))) {
      final String errorMsg = "Loan template type '" + templateType + "' is not supported";
      throw new NotSupportedLoanTemplateTypeException(errorMsg, templateType);
    }
    final Long groupId = null;
    final boolean staffInSelectedOfficeOnly = false;
    final boolean onlyActive = true;
    return this.loansApiResource.template(
        clientId, groupId, productId, templateType, staffInSelectedOfficeOnly, onlyActive, uriInfo);
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Calculate Loan Repayment Schedule | Submit a new Loan Application",
      description =
          "Calculate Loan Repayment Schedule:\n\n"
              + "Calculates Loan Repayment Schedule\n\n"
              + "Mandatory Fields: productId, principal, loanTermFrequency, loanTermFrequencyType,"
              + " numberOfRepayments, repaymentEvery, repaymentFrequencyType,"
              + " interestRatePerPeriod, amortizationType, interestType,"
              + " interestCalculationPeriodType, expectedDisbursementDate,"
              + " transactionProcessingStrategyCode\n\n"
              + "Submit a new Loan Application:\n\n"
              + "Mandatory Fields: clientId, productId, principal, loanTermFrequency,"
              + " loanTermFrequencyType, loanType, numberOfRepayments, repaymentEvery,"
              + " repaymentFrequencyType, interestRatePerPeriod, amortizationType, interestType,"
              + " interestCalculationPeriodType, transactionProcessingStrategyCode,"
              + " expectedDisbursementDate, submittedOnDate, loanType\n\n"
              + "Additional Mandatory Fields if interest recalculation is enabled for product and"
              + " Rest frequency not same as repayment period: recalculationRestFrequencyDate\n\n"
              + "Additional Mandatory Fields if interest recalculation with interest/fee"
              + " compounding is enabled for product and compounding frequency not same as"
              + " repayment period: recalculationCompoundingFrequencyDate\n\n"
              + "Additional Mandatory Field if Entity-Datatable Check is enabled for the entity of"
              + " type loan: datatables\n\n"
              + "Optional Fields: graceOnPrincipalPayment, graceOnInterestPayment,"
              + " graceOnInterestCharged, linkAccountId, allowPartialPeriodInterestCalculation,"
              + " fixedEmiAmount, maxOutstandingLoanBalance, disbursementData,"
              + " graceOnArrearsAgeing, createStandingInstructionAtDisbursement (requires"
              + " linkedAccountId if set to true)\n\n"
              + "Showing request/response for 'Submit a new Loan Application'")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(implementation = SelfLoansApiResourceSwagger.PostSelfLoansRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation = SelfLoansApiResourceSwagger.PostSelfLoansResponse.class)))
  })
  public String calculateLoanScheduleOrSubmitLoanApplication(
      @QueryParam("command") @Parameter(description = "command") final String commandParam,
      @Context final UriInfo uriInfo,
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    HashMap<String, Object> attr = this.dataValidator.validateLoanApplication(apiRequestBodyAsJson);
    final Long clientId = (Long) attr.get("clientId");
    validateAppSelfServiceUserClientsMapping(clientId);

    String responseJson =
        this.loansApiResource.calculateLoanScheduleOrSubmitLoanApplication(
            commandParam, uriInfo, apiRequestBodyAsJson);

    // Only publish notification if the user actually submitted the loan, not just calculating the
    // schedule
    if (!"calculateLoanSchedule".equalsIgnoreCase(commandParam)) {
      Map<String, Object> contextData = new HashMap<>();
      Long loanId = null;
      try {
        JsonObject reqJson = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
        if (reqJson.has("principal"))
          contextData.put("principal", reqJson.get("principal").getAsBigDecimal());
        if (reqJson.has("productId"))
          contextData.put("productId", reqJson.get("productId").getAsLong());

        JsonObject resJson = JsonParser.parseString(responseJson).getAsJsonObject();
        if (resJson.has("loanId")) loanId = resJson.get("loanId").getAsLong();
        else if (resJson.has("resourceId")) loanId = resJson.get("resourceId").getAsLong();
      } catch (Exception e) {
        log.warn("Failed to parse loan application JSON for notification", e);
      }

      publishLoanEvent(
          SelfServiceNotificationEvent.Type.LOAN_REQUESTED, loanId, contextData, httpRequest);
    }

    return responseJson;
  }

  @PUT
  @Path("{loanId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update a Loan Application",
      description =
          "Loan application can only be modified when in 'Submitted and pending approval' state."
              + " Once the application is approved, the details cannot be changed using this"
              + " method.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfLoansApiResourceSwagger.PutSelfLoansLoanIdRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfLoansApiResourceSwagger.PutSelfLoansLoanIdResponse.class)))
  })
  public String modifyLoanApplication(
      @PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    HashMap<String, Object> attr =
        this.dataValidator.validateModifyLoanApplication(apiRequestBodyAsJson);
    validateAppSelfServiceUserLoanMapping(loanId);
    final Long clientId = (Long) attr.get("clientId");
    if (clientId != null) {
      validateAppSelfServiceUserClientsMapping(clientId);
    }
    final String command = null;
    String responseJson =
        this.loansApiResource.modifyLoanApplication(loanId, command, apiRequestBodyAsJson);

    Map<String, Object> contextData = new HashMap<>();
    try {
      JsonObject reqJson = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
      if (reqJson.has("principal"))
        contextData.put("principal", reqJson.get("principal").getAsBigDecimal());
      if (reqJson.has("productId"))
        contextData.put("productId", reqJson.get("productId").getAsLong());
    } catch (Exception e) {
      log.warn("Failed to parse loan modification JSON for notification", e);
    }

    publishLoanEvent(
        SelfServiceNotificationEvent.Type.LOAN_UPDATED, loanId, contextData, httpRequest);

    return responseJson;
  }

  @POST
  @Path("{loanId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Applicant Withdraws from Loan Application",
      description =
          "Applicant Withdraws from Loan Application\n\n" + "Mandatory Fields: withdrawnOnDate")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfLoansApiResourceSwagger.PostSelfLoansLoanIdRequest.class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfLoansApiResourceSwagger.PostSelfLoansLoanIdResponse.class)))
  })
  public String stateTransitions(
      @PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
      @QueryParam("command") @Parameter(description = "command") final String commandParam,
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {
    if (!is(commandParam, "withdrawnByApplicant")) {
      throw new UnrecognizedQueryParamException("command", commandParam);
    }
    validateAppSelfServiceUserLoanMapping(loanId);
    String responseJson =
        this.loansApiResource.stateTransitions(loanId, commandParam, apiRequestBodyAsJson);

    Map<String, Object> contextData = new HashMap<>();
    publishLoanEvent(
        SelfServiceNotificationEvent.Type.LOAN_WITHDRAWN, loanId, contextData, httpRequest);

    return responseJson;
  }

  private void validateAppSelfServiceUserLoanMapping(final Long loanId) {
    this.selfServiceOwnershipGuard.validateLoanOwnership(loanId);
  }

  private void validateAppSelfServiceUserClientsMapping(final Long clientId) {
    this.selfServiceOwnershipGuard.validateClientOwnership(clientId);
  }

  /**
   * Once a loan is approved the guarantors are part of the bank's security for it (approved and
   * disbursed loans may hold guarantee funds). The borrower may change or remove them only while the
   * application is waiting for approval; after that, bank staff do it.
   */
  private void validateGuarantorsCanBeChanged(final Long loanId, final String action) {
    if (!this.loanRepositoryWrapper.findOneWithNotFoundDetection(loanId).isSubmittedAndPendingApproval()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.selfservice.loan.guarantor." + action + ".not.pending.approval",
          "Guarantors can only be changed or removed while the loan application is waiting for"
              + " approval.",
          loanId);
    }
  }

  private boolean is(final String commandParam, final String commandValue) {
    return StringUtils.isNotBlank(commandParam)
        && commandParam.trim().equalsIgnoreCase(commandValue);
  }

  @GET
  @Path("{loanId}/guarantors")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public List<GuarantorData> retrieveGuarantorDetails(
      @PathParam("loanId") final Long loanId, @Context final UriInfo uriInfo) {

    validateAppSelfServiceUserLoanMapping(loanId);
    return this.guarantorsApiResource.retrieveGuarantorDetails(loanId);
  }

  @GET
  @Path("{loanId}/guarantors/template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Guarantor Details Template",
      description = "Guarantor types and relationship options for adding a guarantor to own loan.")
  public GuarantorData newGuarantorTemplate(@PathParam("loanId") final Long loanId) {
    validateAppSelfServiceUserLoanMapping(loanId);
    return this.guarantorsApiResource.newGuarantorTemplate(loanId);
  }

  @POST
  @Path("{loanId}/guarantors")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create a Guarantor",
      description =
          "Adds a guarantor to own loan. A savings account given as guarantee (savingsId) must"
              + " belong to the self-service user: guarantee funds are held on that account.")
  public CommandProcessingResult createGuarantor(
      @PathParam("loanId") final Long loanId, final GuarantorsRequest guarantorsRequest) {
    validateAppSelfServiceUserLoanMapping(loanId);
    if (guarantorsRequest != null && guarantorsRequest.getSavingsId() != null) {
      // Fineract only checks that the account exists, so without this a user could hold funds
      // on another customer's savings account as the guarantee for their own loan.
      this.selfServiceOwnershipGuard.validateSavingsOwnership(guarantorsRequest.getSavingsId());
    }
    return this.guarantorsApiResource.createGuarantor(loanId, guarantorsRequest);
  }

  @PUT
  @Path("{loanId}/guarantors/{guarantorId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update a Guarantor",
      description =
          "Updates the details (name, address, contact, relationship) of a guarantor of own"
              + " loan, while the loan application is waiting for approval.")
  public CommandProcessingResult updateGuarantor(
      @PathParam("loanId") final Long loanId,
      @PathParam("guarantorId") final Long guarantorId,
      final GuarantorsRequest guarantorsRequest) {
    validateAppSelfServiceUserLoanMapping(loanId);
    validateGuarantorsCanBeChanged(loanId, "update");
    return this.guarantorsApiResource.updateGuarantor(loanId, guarantorId, guarantorsRequest);
  }

  @DELETE
  @Path("{loanId}/guarantors/{guarantorId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Remove a Guarantor",
      description =
          "Removes a guarantor of own loan, or only one guarantee fund when guarantorFundingId"
              + " is given, while the loan application is waiting for approval.")
  public CommandProcessingResult deleteGuarantor(
      @PathParam("loanId") final Long loanId,
      @PathParam("guarantorId") final Long guarantorId,
      @QueryParam("guarantorFundingId") final Long guarantorFundingId) {
    validateAppSelfServiceUserLoanMapping(loanId);
    validateGuarantorsCanBeChanged(loanId, "delete");
    return this.guarantorsApiResource.deleteGuarantor(loanId, guarantorId, guarantorFundingId);
  }

  // --- Helper Methods for Notifications ---

  private void publishLoanEvent(
      SelfServiceNotificationEvent.Type type,
      Long loanId,
      Map<String, Object> contextData,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);

      contextData.put("loanId", loanId != null ? loanId : "");

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
