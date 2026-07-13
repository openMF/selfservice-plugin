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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.apache.fineract.selfservice.account.exception.BeneficiaryTransferLimitExceededException;
import org.apache.fineract.selfservice.account.exception.DailyTPTTransactionAmountLimitExceededException;
import org.apache.fineract.selfservice.account.service.AccountTransferQuoteService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferReadService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferWritePlatformService;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTReadPlatformService;
import org.apache.fineract.selfservice.account.service.SinpeExternalApiClient;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Path("/v1/self/accounttransfers")
@Component
@Tag(
    name = "Self Account transfer",
    description = "Endpoints for 3-step account transfers (Prepare, Quote, Confirm) and legacy account transfers")
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferApiResource {

  // New dependencies
  private final PlatformSelfServiceSecurityContext context;
  private final SelfAccountTransferWritePlatformService transferWritePlatformService;
  private final AccountTransferQuoteService quoteService;
  private final SinpeExternalApiClient sinpeExternalApiClient;
  private final SelfServiceRegistrationRepository registrationRepository;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

  // Legacy dependencies restored for backward compatibility
  private final DefaultToApiJsonSerializer<SelfAccountTransferData> toApiJsonSerializer;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  private final SelfAccountTransferReadService selfAccountTransferReadService;
  private final ApiRequestParameterHelper apiRequestParameterHelper;
  private final SelfAccountTransferDataValidator dataValidator;
  private final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  private final ConfigurationDomainService configurationDomainService;
  private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;

  // ==========================================
  // NEW 3-STEP ENDPOINTS
  // ==========================================

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

    // Basic validation
    if (request.getTransferAmount() == null
        || request.getTransferAmount().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Transfer amount must be greater than zero.");
    }

    // Return the prepared data (in a real scenario, you might fetch account details here)
    return new Gson().toJson(request);
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
    AccountTransferQuoteResponse quote = quoteService.calculateFee(request);

    return new Gson().toJson(quote);
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
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    user.validateHasCreatePermission("ACCOUNTTRANSFER");

    AccountTransferConfirmRequest request =
        new Gson().fromJson(apiRequestBodyAsJson, AccountTransferConfirmRequest.class);

    // STEP 1: If OTP is missing, generate and send it
    if (StringUtils.isBlank(request.getOtp())) {
      return generateAndSendOtp(request, user, httpRequest);
    }

    // STEP 2: Validate OTP
    validateOtp(request, user);

    // STEP 3: Execute Transfer
    CommandProcessingResult result;
    if ("SINPE_MOVIL".equals(request.getTransferType())) {
      result = executeSinpeTransfer(request, user);
    } else {
      // FIX: Use the new service that bypasses PortfolioCommandSourceWritePlatformService
      result = transferWritePlatformService.executeInternalTransfer(request);
    }

    // STEP 4: Publish Success Notification
    publishTransferEvent(result, request, httpRequest);

    return new Gson().toJson(result);
  }

  private String generateAndSendOtp(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      HttpServletRequest httpRequest) {
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);

    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    SelfServiceRegistration registration =
        SelfServiceRegistration.instance(
            client,
            client.getAccountNumber(),
            client.getFirstname(),
            client.getMiddlename(),
            client.getLastname(),
            request.getToPhoneNumber() != null
                ? request.getToPhoneNumber()
                : request.getToAccountId(),
            user.getEmail(),
            otp,
            otp,
            user.getUsername(),
            "TRANSFER_OTP",
            SelfServiceRequestType.ACCOUNT_TRANSFER,
            expiry);
    registrationRepository.saveAndFlush(registration);

    // Send OTP Notification
    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put("transferAmount", request.getTransferAmount());

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.TRANSFER_OTP,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            extractMobile(user),
            determineMode(user.getEmail(), extractMobile(user)),
            extractClientIp(httpRequest),
            LocaleContextHolder.getLocale(),
            contextData));

    Map<String, Object> response = new HashMap<>();
    response.put("status", "AWAITING_OTP");
    response.put("message", "OTP sent successfully. Please check your SMS or Email.");
    return new Gson().toJson(response);
  }

  private void validateOtp(AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SelfServiceRegistration registration =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                client.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER, request.getOtp())
            .orElse(null);

    if (registration == null
        || registration.isConsumed()
        || registration.isExpired(DateUtils.getLocalDateTimeOfSystem())) {

      final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
      final DataValidatorBuilder baseDataValidator =
          new DataValidatorBuilder(dataValidationErrors).resource("otp");
      baseDataValidator
          .reset()
          .parameter("otp")
          .value(request.getOtp())
          .failWithCode("invalid.or.expired", "Invalid or expired OTP.");

      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    registration.markConsumed();
    registrationRepository.saveAndFlush(registration);
  }

  private CommandProcessingResult executeSinpeTransfer(
      AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SinpeTransferRequest sinpeRequest =
        SinpeTransferRequest.builder()
            .originCustomerId(
                client.getExternalId() != null
                    ? client.getExternalId().getValue()
                    : client.getAccountNumber())
            .originCustomerName(client.getFullname())
            .originIban(request.getFromAccountId())
            .destinationPhone(request.getToPhoneNumber())
            .amount(request.getTransferAmount())
            .currencyCode("CRC")
            .description(request.getTransferDescription())
            .debitIBAN(true)
            .customData(List.of(new SinpeTransferRequest.CustomData("Source", "SelfServiceApp")))
            .build();

    sinpeExternalApiClient.transferToPhone(sinpeRequest);

    return new CommandProcessingResultBuilder().withEntityId(0L).build();
  }

  private void publishTransferEvent(
      CommandProcessingResult result,
      AccountTransferConfirmRequest request,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put("transactionAmount", request.getTransferAmount());
      contextData.put("transferDescription", request.getTransferDescription());
      contextData.put("fromAccountNumber", request.getFromAccountId());
      contextData.put(
          "toAccountNumber",
          request.getToAccountId() != null ? request.getToAccountId() : request.getToPhoneNumber());
      contextData.put(
          "transferId",
          result.getResourceId() != null
              ? result.getResourceId()
              : "EXT-" + System.currentTimeMillis());

      applicationEventPublisher.publishEvent(
          SelfServiceNotificationEvent.withTenantContext(
              this,
              SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS,
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
      log.warn("Failed to publish transfer notification event", e);
    }
  }

  // ==========================================
  // LEGACY ENDPOINTS RESTORED
  // ==========================================

  @GET
  @Path("template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Account Transfer Template",
      description =
          "Returns list of loan/savings accounts that can be used for account transfer\n\n"
              + "Example Requests:\n\n"
              + "self/accounttransfers/template\n")
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

    if (type.equals("tpt")) {
      Collection<SelfAccountTemplateData> tptTemplateData =
          this.tptBeneficiaryReadPlatformService.retrieveTPTSelfAccountTemplateData(user);
      return this.toApiJsonSerializer.serialize(
          settings, new SelfAccountTransferData(selfTemplateData, tptTemplateData));
    }

    return this.toApiJsonSerializer.serialize(
        settings, new SelfAccountTransferData(selfTemplateData, selfTemplateData));
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create new Transfer",
      description =
          "Ability to create new transfer of monetary funds from one account to another.\n\n"
              + "Example Requests:\n\n"
              + "self/accounttransfers/\n")
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
  public CommandProcessingResult create(
      @DefaultValue("") @QueryParam("type") @Parameter(name = "type") final String type,
      final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    Map<String, Object> params = this.dataValidator.validateCreate(type, apiRequestBodyAsJson);
    if (type.equals("tpt")) {
      checkForLimits(params);
    }
    final CommandWrapper commandRequest =
        new CommandWrapperBuilder().createAccountTransfer().withJson(apiRequestBodyAsJson).build();

    CommandProcessingResult result =
        commandsSourceWritePlatformService.logCommandSource(commandRequest);

    // PUBLISH NOTIFICATION EVENT AFTER SUCCESSFUL TRANSFER
    publishTransferEvent(result, params, httpRequest);

    return result;
  }

  private void publishTransferEvent(
      CommandProcessingResult result, Map<String, Object> params, HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);

      Map<String, Object> contextData = new HashMap<>();

      // Extract details directly from the validated params map.
      // The dataValidator returns the raw values extracted from the JSON payload using their
      // original key names.
      Object fromAccountIdObj = params.get("fromAccountId");
      Object toAccountIdObj = params.get("toAccountId");
      Object transferAmountObj = params.get("transferAmount");
      Object transferDateObj = params.get("transferDate");
      Object transferDescObj = params.get("transferDescription");

      // Resolve Account Numbers
      // JSON payload, fromAccountId and toAccountId contain the actual account numbers
      // ("000000001", "000000003").
      // We convert them to String to ensure they are safely passed to the template engine.
      String fromAccountNo = fromAccountIdObj != null ? String.valueOf(fromAccountIdObj) : "";
      String toAccountNo = toAccountIdObj != null ? String.valueOf(toAccountIdObj) : "";

      // Populate context data with all available transfer details
      contextData.put("transactionAmount", transferAmountObj != null ? transferAmountObj : "");
      contextData.put("transactionDate", transferDateObj != null ? transferDateObj : "");
      contextData.put("transferDescription", transferDescObj != null ? transferDescObj : "");
      contextData.put("fromAccountNumber", fromAccountNo);
      contextData.put("toAccountNumber", toAccountNo);
      contextData.put("transferId", result.getResourceId() != null ? result.getResourceId() : "");

      applicationEventPublisher.publishEvent(
          SelfServiceNotificationEvent.withTenantContext(
              this,
              SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS,
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
      // Log warning but do not fail the transfer if notification publishing fails
      log.warn("Failed to publish legacy transfer notification event", e);
    }
  }

  private void checkForLimits(Map<String, Object> params) {
    SelfAccountTemplateData fromAccount = (SelfAccountTemplateData) params.get("fromAccount");
    SelfAccountTemplateData toAccount = (SelfAccountTemplateData) params.get("toAccount");
    LocalDate transactionDate = (LocalDate) params.get("transactionDate");
    BigDecimal transactionAmount = (BigDecimal) params.get("transactionAmount");

    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    Long transferLimit =
        this.tptBeneficiaryReadPlatformService.getTransferLimit(
            user.getId(), toAccount.getAccountId(), toAccount.getAccountType());
    if (transferLimit != null && transferLimit > 0) {
      if (transactionAmount.compareTo(new BigDecimal(transferLimit)) > 0) {
        throw new BeneficiaryTransferLimitExceededException();
      }
    }

    if (this.configurationDomainService.isDailyTPTLimitEnabled()) {
      Long dailyTPTLimit = this.configurationDomainService.getDailyTPTLimit();
      if (dailyTPTLimit != null && dailyTPTLimit > 0) {
        BigDecimal dailyTPTLimitBD = new BigDecimal(dailyTPTLimit);
        BigDecimal totTransactionAmount =
            this.accountTransfersReadPlatformService.getTotalTransactionAmount(
                fromAccount.getAccountId(), fromAccount.getAccountType(), transactionDate);
        BigDecimal totalSoFar =
            totTransactionAmount == null ? BigDecimal.ZERO : totTransactionAmount;
        if (dailyTPTLimitBD.compareTo(totalSoFar) <= 0
            || dailyTPTLimitBD.compareTo(totalSoFar.add(transactionAmount)) < 0) {
          throw new DailyTPTTransactionAmountLimitExceededException(
              fromAccount.getAccountId(), fromAccount.getAccountType());
        }
      }
    }
  }

  // ==========================================
  // SHARED UTILITY METHODS
  // ==========================================

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