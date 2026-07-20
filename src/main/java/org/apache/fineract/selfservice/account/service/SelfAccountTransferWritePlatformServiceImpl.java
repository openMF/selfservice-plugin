package org.apache.fineract.selfservice.account.service;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.apache.fineract.selfservice.account.exception.BeneficiaryTransferLimitExceededException;
import org.apache.fineract.selfservice.account.exception.DailyTPTTransactionAmountLimitExceededException;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountForFeesRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferWritePlatformServiceImpl implements SelfAccountTransferWritePlatformService {

  private final PlatformSelfServiceSecurityContext context;
  private final AccountTransferQuoteService quoteService;
  private final SinpeExternalApiClient sinpeExternalApiClient;
  private final SelfServiceRegistrationRepository registrationRepository;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;
  private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
  private final ExternalIdFactory externalIdFactory;
  private final SelfAccountTransferDataValidator dataValidator;
  private final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  private final ConfigurationDomainService configurationDomainService;
  private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;
  private final ClientReadPlatformService clientReadPlatformService;
  private final OfficeReadPlatformService officeReadPlatformService;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  private final PinExternalTransferService pinExternalTransferService;
  private final SelfServiceAccountForFeesRepository externalServicePropertiesRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Gson gson = new Gson();

  @Override
  @Transactional
  public Object prepareTransfer(final AccountTransferPrepareRequest request) {
    log.info("PREPARE: Processing new incoming transfer request from DTO.");

    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();

    final String fromAccount = request.getFromAccount();
    final String toAccount = request.getToAccount();
    final BigDecimal transferAmount = request.getTransferAmount();
    final String transferType = request.getTransferType();
    final String currencyCode = request.getCurrencyCode();
    final String transferDescription = request.getTransferDescription();
    final String reference = request.getReference();

    if (transferAmount == null || transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("The transfer amount (transferAmount) must be greater than zero.");
    }
    if (toAccount == null || toAccount.isBlank()) {
      throw new IllegalArgumentException("The destination account or number (toAccount) is required.");
    }
    if (fromAccount == null || fromAccount.isBlank()) {
      throw new IllegalArgumentException("The source account (fromAccount) is required.");
    }

    validateDestinationAccount(currentUser.getId(), toAccount, transferType);

    log.info("PREPARE: Validating status and funds of the local source account: {}", fromAccount);

    Map<String, Object> prepareResponse = new HashMap<>();
    prepareResponse.put("status", "PREPARED");
    prepareResponse.put("fromAccount", fromAccount);
    prepareResponse.put("toAccount", toAccount);
    prepareResponse.put("transferAmount", transferAmount);
    prepareResponse.put("transferType", transferType);
    prepareResponse.put("currencyCode", currencyCode);
    prepareResponse.put("message", "The destination account was verified and the status is suitable to proceed with the quote.");

    log.info("PREPARE: Transfer successfully validated and prepared for destination: {}", toAccount);
    return prepareResponse;
  }

  @Override
  @Transactional
  public Object quoteTransfer(final AccountTransferPrepareRequest request) {
    log.info("QUOTE: Starting quote for channel: {}", request.getTransferType());

    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();

    final AccountTransferQuoteResponse quote = this.quoteService.calculateFee(request);

    log.info("QUOTE: Quote calculated. Triggering new security OTP dispatch.");

    String destinationTarget = "SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())
            ? request.getToPhoneNumber()
            : request.getToAccount();

    generateAndSendOtpForQuote(currentUser, destinationTarget, request.getTransferAmount());

    return this.gson.toJson(quote);
  }

  @Override
  @Transactional
  public Object confirmTransfer(AccountTransferConfirmRequest request, HttpServletRequest httpRequest) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    user.validateHasCreatePermission("ACCOUNTTRANSFER");

    validateOtp(request, user);

    BigDecimal feeAmountFromClient = request.getFeeAmount() != null ? request.getFeeAmount() : BigDecimal.ZERO;

    log.info("CONFIRM: Starting two-step processing for channel: {} | Fee: {}",
            request.getTransferType(), feeAmountFromClient);

    String cleanDestination = request.getToAccount() != null ? request.getToAccount().replaceAll("\\s+", "") : "";

    // Handle PIN transfer separately to return the custom response structure and trigger notifications
    if ("PIN".equalsIgnoreCase(request.getTransferType())) {
      return executePinTransfer(request, user, httpRequest);
    }

    CommandProcessingResult result;
    if (isSameBankIbanAccount(cleanDestination) || "SAME_BANK".equalsIgnoreCase(request.getTransferType())) {
      log.info("CONFIRM -> Internal account detected. Executing local transfer.");
      result = executeInternalTransfer(request, user);
    } else if ("SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())) {
      result = executeSinpeTransfer(request, user);
    } else {
      result = executeInternalTransfer(request, user);
    }

    if (feeAmountFromClient.compareTo(BigDecimal.ZERO) > 0) {
      log.info("ACCOUNTING CONFIRM: Shifting fee of {} {} to the collector account configured in c_external_service.",
              feeAmountFromClient, request.getCurrencyCode());

      executeCommissionChargeViaSameBank(request, feeAmountFromClient);
    }

    publishFastPaymentTransferEvent(result, request, httpRequest);
    return result;
  }

  @Override
  @Transactional
  public CommandProcessingResult createTransfer(String type, String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
    Map<String, Object> params = dataValidator.validateCreate(type, apiRequestBodyAsJson);
    if (type.equals("tpt")) {
      checkForLimits(params);
    }

    final CommandWrapper commandRequest = new CommandWrapperBuilder().createAccountTransfer().withJson(apiRequestBodyAsJson).build();
    CommandProcessingResult result = commandsSourceWritePlatformService.logCommandSource(commandRequest);

    publishTransferEvent(result, params, params, httpRequest);
    return result;
  }

  private String generateAndSendOtp(AccountTransferConfirmRequest request, AppSelfServiceUser user, HttpServletRequest httpRequest) {
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);

    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SelfServiceRegistration registration = SelfServiceRegistration.instance(
            client, client.getAccountNumber(), client.getFirstname(), client.getMiddlename(), client.getLastname(),
            request.getFromAccount(),
            user.getEmail(), otp, otp, user.getUsername(), "TRANSFER_OTP", SelfServiceRequestType.ACCOUNT_TRANSFER, expiry);
    registrationRepository.saveAndFlush(registration);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put("transferAmount", request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");

    applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
            this, SelfServiceNotificationEvent.Type.TRANSFER_OTP, user.getId(), user.getFirstname(), user.getLastname(),
            user.getUsername(), user.getEmail(), extractMobile(user), determineMode(user.getEmail(), extractMobile(user)),
            extractClientIp(httpRequest), LocaleContextHolder.getLocale(), contextData));

    Map<String, Object> response = new HashMap<>();
    response.put("status", "AWAITING_OTP");
    response.put("message", "OTP sent successfully. Please check your SMS or Email.");
    return gson.toJson(response);
  }

  private void validateOtp(AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SelfServiceRegistration registration = registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                    client.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER, request.getOtp())
            .orElse(null);

    if (registration == null || registration.isConsumed() || registration.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
      final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("otp");
      baseDataValidator.reset().parameter("otp").value(request.getOtp()).failWithCode("invalid.or.expired", "Invalid or expired OTP.");
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    registration.markConsumed();
    registrationRepository.saveAndFlush(registration);
  }

  private CommandProcessingResult executeSinpeTransfer(AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SinpeTransferRequest sinpeRequest = SinpeTransferRequest.builder()
            .originCustomerId(client.getExternalId() != null ? client.getExternalId().getValue() : client.getAccountNumber())
            .originCustomerName(client.getFullname())
            .originIban(request.getFromAccount())
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

  private Object executePinTransfer(AccountTransferConfirmRequest request, AppSelfServiceUser user, HttpServletRequest httpRequest) {
    log.info("CONFIRM PIN: Starting PIN flow with strict destination and origin metadata validation.");

    try {
      Client client = user.getAppUserClientMappings().iterator().next().getClient();

      boolean yaEsBeneficiario = this.isAlreadyRegisteredAsBeneficiary(user.getId(), request.getToAccount());
      if (yaEsBeneficiario) {
        log.warn("CONFIRM PIN: The destination account {} is already registered in beneficiaries.", request.getToAccount());
      }

      String destinationName = null;
      String destinationId = null;
      String destinationIdType = null;
      String dynamicCurrencyCode = null;

      try {
        log.info("CONFIRM PIN: Invoking getAccountInfo to resolve destination IBAN metadata.");
        String infoJsonResponse = this.pinExternalTransferService.getAccountInfo(request.getToAccount());

        if (infoJsonResponse != null && !infoJsonResponse.contains("\"disabled\"") && !infoJsonResponse.contains("\"error\"")) {
          Map<String, Object> infoMap = this.gson.fromJson(infoJsonResponse, Map.class);

          if (infoMap != null) {
            if (infoMap.get("holder") != null) destinationName = infoMap.get("holder").toString();
            if (infoMap.get("holderId") != null) destinationId = infoMap.get("holderId").toString();
            if (infoMap.get("currencyCode") != null) dynamicCurrencyCode = infoMap.get("currencyCode").toString();

            if (infoMap.get("holderIdType") != null) {
              Double idTypeDouble = Double.parseDouble(infoMap.get("holderIdType").toString());
              destinationIdType = String.valueOf(idTypeDouble.intValue());
            }
          }
        }
      } catch (Exception e) {
        log.error("CONFIRM PIN: Error querying account info on the external gateway: ", e);
        throw new IllegalArgumentException("Destination account data could not be verified. Please try again later.");
      }

      if (StringUtils.isBlank(destinationName) || StringUtils.isBlank(dynamicCurrencyCode)) {
        log.error("CONFIRM PIN: Aborting transfer. Incomplete destination data. Holder: {}, Currency: {}", destinationName, dynamicCurrencyCode);
        throw new IllegalArgumentException("The destination account did not return valid holder or currency information. Transfer canceled.");
      }

      String originName = null;

      if (client != null) {
        if (StringUtils.isNotBlank(client.getDisplayName())) {
          originName = client.getDisplayName();
        } else if (StringUtils.isNotBlank(client.getFullname())) {
          originName = client.getFullname();
        } else {
          StringBuilder sb = new StringBuilder();
          if (StringUtils.isNotBlank(client.getFirstname())) sb.append(client.getFirstname().trim());
          if (StringUtils.isNotBlank(client.getMiddlename())) sb.append(" ").append(client.getMiddlename().trim());
          if (StringUtils.isNotBlank(client.getLastname())) sb.append(" ").append(client.getLastname().trim());
          originName = sb.toString().trim();
        }
      }

      if (StringUtils.isBlank(originName)) {
        log.error("CONFIRM PIN: Aborting transfer. Could not determine the origin client name in Fineract.");
        throw new IllegalArgumentException("Origin client identity could not be verified. Transfer canceled to avoid external rejections.");
      }

      org.apache.fineract.selfservice.account.data.PinTransferRequest pinRequest =
              new org.apache.fineract.selfservice.account.data.PinTransferRequest();

      pinRequest.setAmount(request.getTransferAmount());
      pinRequest.setCurrency(dynamicCurrencyCode);
      pinRequest.setDescription(StringUtils.isNotBlank(request.getTransferDescription()) ? request.getTransferDescription() : "PIN Transfer");

      pinRequest.setOriginCustomerName(originName);
      pinRequest.setOriginIban(request.getFromAccount().replaceAll("\\s+", ""));
      pinRequest.setOriginCustomerId(client.getExternalId() != null ? client.getExternalId().getValue() : client.getAccountNumber());
      pinRequest.setOriginIdType("0");
      pinRequest.setOriginEmail(user.getEmail() != null ? user.getEmail() : "");

      pinRequest.setDestinationIban(request.getToAccount().replaceAll("\\s+", ""));
      pinRequest.setDestinationCustomerName(destinationName);
      pinRequest.setDestinationCustomerId(destinationId != null ? destinationId : "0");
      pinRequest.setDestinationIdType(destinationIdType != null ? destinationIdType : "0");
      pinRequest.setDestinationEmail("");

      Map<String, String> sinpeProps = getSinpeProperties();
      String branchName = sinpeProps.getOrDefault("branchName", "Default");

      pinRequest.setBranchName(branchName);
      pinRequest.setReference(StringUtils.isNotBlank(request.getReference()) ? request.getReference() : "Ref-PIN");
      pinRequest.setDebitIban(true);

      log.info("CONFIRM PIN: Data successfully validated. Dispatching funds to the external gateway...");
      String pinServiceResponse = this.pinExternalTransferService.executePinTransfer(pinRequest);

      if (pinServiceResponse != null && (pinServiceResponse.contains("\"disabled\"") || pinServiceResponse.contains("\"error\""))) {
        throw new IllegalArgumentException("The external PIN gateway rejected the transaction.");
      }

      log.info("CONFIRM PIN: Successfully processed and debited by the external service.");

      // Parse the external response and wrap it in the desired structure
      Map<String, Object> externalData = gson.fromJson(pinServiceResponse, Map.class);
      
      Map<String, Object> response = new HashMap<>();
      response.put("transferType", "PIN");
      response.put("data", externalData);

      // Trigger multi-channel notification for successful PIN transfer
      publishPinTransferEvent(request, user, httpRequest, externalData);

      return response;

    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      log.error("CONFIRM PIN: Unexpected critical error executing PIN transfer: ", e);
      throw new RuntimeException("Error processing external PIN transfer.", e);
    }
  }

  private CommandProcessingResult executeInternalTransfer(AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    Long fromClientId = client.getId();
    Long fromOfficeId = client.getOffice().getId();

    Map<String, Object> commandData = new HashMap<>();
    commandData.put("fromOfficeId", fromOfficeId);
    commandData.put("fromClientId", fromClientId);
    commandData.put("fromAccountType", request.getFromAccountType() != null ? request.getFromAccountType() : 2);
    commandData.put("fromAccountId", request.getFromAccount());
    commandData.put("toAccountType", request.getToAccountType() != null ? request.getToAccountType() : 2);
    commandData.put("toAccountId", request.getToAccount());
    commandData.put("transferAmount", request.getTransferAmount());
    commandData.put("transferDate", request.getTransferDate());
    commandData.put("transferDescription", request.getTransferDescription() != null ? request.getTransferDescription() : "Internal Transfer");
    commandData.put("locale", request.getLocale() != null ? request.getLocale() : "es");
    commandData.put("dateFormat", request.getDateFormat() != null ? request.getDateFormat() : "dd-MM-yyyy");

    String apiRequestBodyAsJson = gson.toJson(commandData);

    final CommandWrapper commandRequest = new CommandWrapperBuilder()
            .createAccountTransfer()
            .withJson(apiRequestBodyAsJson)
            .build();
    return commandsSourceWritePlatformService.logCommandSource(commandRequest);
  }

  private void publishFastPaymentTransferEvent(CommandProcessingResult result, AccountTransferConfirmRequest request, HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      
      contextData.put("transactionAmount", request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put("transferDescription", StringUtils.isNotBlank(request.getTransferDescription()) ? request.getTransferDescription() : "N/A");
      contextData.put("fromAccountNumber", StringUtils.isNotBlank(request.getFromAccount()) ? request.getFromAccount() : "N/A");
      contextData.put("toAccountNumber", StringUtils.isNotBlank(request.getToAccount()) ? request.getToAccount() : (StringUtils.isNotBlank(request.getToPhoneNumber()) ? request.getToPhoneNumber() : "N/A"));
      contextData.put("transferId", result.getResourceId() != null ? result.getResourceId().toString() : "N/A");
      contextData.put("transactionDate", StringUtils.isNotBlank(request.getTransferDate()) ? request.getTransferDate() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      try {
        Client client = user.getAppUserClientMappings().iterator().next().getClient();
        contextData.put("fromClientName", StringUtils.isNotBlank(client.getDisplayName()) ? client.getDisplayName() : "N/A");
      } catch (Exception e) {
        contextData.put("fromClientName", "N/A");
      }
      
      contextData.put("toClientName", "N/A");
      contextData.put("fromOfficeName", "N/A");
      contextData.put("toOfficeName", "N/A");

      applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
              this, SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS, user.getId(), user.getFirstname(), user.getLastname(),
              user.getUsername(), user.getEmail(), mobileNumber, emailMode, ipAddress, LocaleContextHolder.getLocale(), contextData));
    } catch (Exception e) {
      log.warn("Failed to publish transfer notification event", e);
    }
  }

  /**
   * Publishes a notification event specifically for successful PIN transfers.
   * Extracts the reference ID from the external gateway response to populate the transferId.
   */
  private void publishPinTransferEvent(AccountTransferConfirmRequest request, AppSelfServiceUser user, HttpServletRequest httpRequest, Map<String, Object> externalData) {
    try {
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      
      contextData.put("transactionAmount", request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put("transferDescription", StringUtils.isNotBlank(request.getTransferDescription()) ? request.getTransferDescription() : "N/A");
      contextData.put("fromAccountNumber", StringUtils.isNotBlank(request.getFromAccount()) ? request.getFromAccount() : "N/A");
      contextData.put("toAccountNumber", StringUtils.isNotBlank(request.getToAccount()) ? request.getToAccount() : "N/A");
      
      // Extract the most relevant reference ID from the external gateway response
      String transferId = "N/A";
      if (externalData != null) {
          if (externalData.get("channelRefNumber") != null) {
              transferId = externalData.get("channelRefNumber").toString();
          } else if (externalData.get("sinpeRefNumber") != null) {
              transferId = externalData.get("sinpeRefNumber").toString();
          } else if (externalData.get("operationId") != null) {
              transferId = externalData.get("operationId").toString();
          }
      }
      contextData.put("transferId", transferId);
      contextData.put("transactionDate", StringUtils.isNotBlank(request.getTransferDate()) ? request.getTransferDate() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      try {
        Client client = user.getAppUserClientMappings().iterator().next().getClient();
        contextData.put("fromClientName", StringUtils.isNotBlank(client.getDisplayName()) ? client.getDisplayName() : "N/A");
      } catch (Exception e) {
        contextData.put("fromClientName", "N/A");
      }
      
      contextData.put("toClientName", "N/A");
      contextData.put("fromOfficeName", "N/A");
      contextData.put("toOfficeName", "N/A");

      applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
              this, SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS, user.getId(), user.getFirstname(), user.getLastname(),
              user.getUsername(), user.getEmail(), mobileNumber, emailMode, ipAddress, LocaleContextHolder.getLocale(), contextData));
              
      log.info("CONFIRM PIN: Notification event published successfully for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to publish PIN transfer notification event", e);
    }
  }

  private void publishTransferEvent(CommandProcessingResult result, Map<String, Object> params,
                                    Map<String, Object> originalParams, HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();

      contextData.put("transactionAmount", getFieldValue(params, originalParams, "transactionAmount", "transferAmount", "amount"));
      contextData.put("transferDescription", getFieldValue(params, originalParams, "transferDescription", "description"));
      contextData.put("transactionDate", getFieldValue(params, originalParams, "transactionDate", "transferDate"));
      contextData.put("fromAccountNumber", getFieldValue(params, originalParams, "fromAccountNumber", "fromAccountId"));
      contextData.put("toAccountNumber", getFieldValue(params, originalParams, "toAccountNumber", "toAccountId"));
      contextData.put("transferId", result.getResourceId() != null ? result.getResourceId().toString() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      resolveClientAndOfficeNames(contextData, params, originalParams);

      log.debug("Publishing transfer notification with contextData: {}", contextData);

      applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
              this, SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS, user.getId(), user.getFirstname(), user.getLastname(),
              user.getUsername(), user.getEmail(), mobileNumber, emailMode, ipAddress, LocaleContextHolder.getLocale(), contextData));
    } catch (Exception e) {
      log.error("Failed to publish transfer notification event", e);
    }
  }

  private void resolveClientAndOfficeNames(Map<String, Object> contextData,
                                           Map<String, Object> params, Map<String, Object> originalParams) {
    contextData.putIfAbsent("fromClientName", "N/A");
    contextData.putIfAbsent("fromOfficeName", "N/A");
    contextData.putIfAbsent("toClientName", "N/A");
    contextData.putIfAbsent("toOfficeName", "N/A");

    try {
      Long fromClientId = getLongValue(params, originalParams, "fromClientId");
      if (fromClientId != null) {
        try {
          ClientData fromClient = clientReadPlatformService.retrieveOne(fromClientId);
          if (fromClient != null) {
            if (StringUtils.isNotBlank(fromClient.getDisplayName())) {
              contextData.put("fromClientName", fromClient.getDisplayName());
            }
            if (fromClient.getOfficeId() != null) {
              try {
                String fromOfficeName = officeReadPlatformService.retrieveOffice(fromClient.getOfficeId()).getName();
                if (StringUtils.isNotBlank(fromOfficeName)) {
                  contextData.put("fromOfficeName", fromOfficeName);
                }
              } catch (Exception e) {
                log.debug("Could not fetch fromOfficeName for officeId: {}", fromClient.getOfficeId());
              }
            }
          }
        } catch (Exception e) {
          log.warn("Failed to resolve FROM client details for clientId: {}", fromClientId, e);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to resolve FROM client/office names: {}", e.getMessage());
    }

    try {
      Long toClientId = getLongValue(params, originalParams, "toClientId");
      if (toClientId != null) {
        try {
          ClientData toClient = clientReadPlatformService.retrieveOne(toClientId);
          if (toClient != null) {
            if (StringUtils.isNotBlank(toClient.getDisplayName())) {
              contextData.put("toClientName", toClient.getDisplayName());
            }
            if (toClient.getOfficeId() != null) {
              try {
                String toOfficeName = officeReadPlatformService.retrieveOffice(toClient.getOfficeId()).getName();
                if (StringUtils.isNotBlank(toOfficeName)) {
                  contextData.put("toOfficeName", toOfficeName);
                }
              } catch (Exception e) {
                log.debug("Could not fetch toOfficeName for officeId: {}", toClient.getOfficeId());
              }
            }
          }
        } catch (Exception e) {
          log.warn("Failed to resolve TO client details for clientId: {}", toClientId, e);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to resolve TO client/office names: {}", e.getMessage());
    }
  }

  private Object getFieldValue(Map<String, Object> params, Map<String, Object> originalParams, String... possibleKeys) {
    for (String key : possibleKeys) {
      Object value = params.get(key);
      if (isNotEmpty(value)) {
        return value;
      }
      value = originalParams.get(key);
      if (isNotEmpty(value)) {
        return value;
      }
    }
    return "N/A";
  }

  private Long getLongValue(Map<String, Object> params, Map<String, Object> originalParams, String key) {
    Object value = params.get(key);
    if (value == null) {
      value = originalParams.get(key);
    }
    if (value != null) {
      if (value instanceof Number) {
        return ((Number) value).longValue();
      }
      try {
        return Long.valueOf(value.toString());
      } catch (NumberFormatException e) {
        log.debug("Could not convert value '{}' for key '{}' to Long", value, key);
      }
    }
    return null;
  }

  private boolean isNotEmpty(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof String) {
      return !((String) value).isEmpty();
    }
    return true;
  }

  private void checkForLimits(Map<String, Object> params) {
    SelfAccountTemplateData fromAccount = (SelfAccountTemplateData) params.get("fromAccount");
    SelfAccountTemplateData toAccount = (SelfAccountTemplateData) params.get("toAccount");
    LocalDate transactionDate = (LocalDate) params.get("transactionDate");
    BigDecimal transactionAmount = (BigDecimal) params.get("transactionAmount");

    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Long transferLimit = tptBeneficiaryReadPlatformService.getTransferLimit(user.getId(), toAccount.getAccountId(), toAccount.getAccountType());
    if (transferLimit != null && transferLimit > 0 && transactionAmount.compareTo(new BigDecimal(transferLimit)) > 0) {
      throw new BeneficiaryTransferLimitExceededException();
    }

    if (configurationDomainService.isDailyTPTLimitEnabled()) {
      Long dailyTPTLimit = configurationDomainService.getDailyTPTLimit();
      if (dailyTPTLimit != null && dailyTPTLimit > 0) {
        BigDecimal dailyTPTLimitBD = new BigDecimal(dailyTPTLimit);
        BigDecimal totTransactionAmount = accountTransfersReadPlatformService.getTotalTransactionAmount(
                fromAccount.getAccountId(), fromAccount.getAccountType(), transactionDate);
        BigDecimal totalSoFar = totTransactionAmount == null ? BigDecimal.ZERO : totTransactionAmount;
        if (dailyTPTLimitBD.compareTo(totalSoFar) <= 0 || dailyTPTLimitBD.compareTo(totalSoFar.add(transactionAmount)) < 0) {
          throw new DailyTPTTransactionAmountLimitExceededException(fromAccount.getAccountId(), fromAccount.getAccountType());
        }
      }
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
    String pref = env.getProperty("fineract.selfservice.notification.login.delivery-preference", "email");
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

  private void validateDestinationAccount(Long appUserId, String destinationAccount, String transferType) {
    boolean isBeneficiaryActive = isAlreadyRegisteredAsBeneficiary(appUserId, destinationAccount);

    if (isBeneficiaryActive) {
      log.info("PREPARE: The destination account {} is already registered and active as a beneficiary.", destinationAccount);
      return;
    }

    log.info("PREPARE: Destination account not previously registered. Evaluating channel for: {}", transferType);

    String cleanAccount = destinationAccount.replaceAll("\\s+", "");

    if ("PIN".equalsIgnoreCase(transferType) || "SAME_BANK".equalsIgnoreCase(transferType) || isSameBankIbanAccount(cleanAccount)) {
      log.info("PREPARE [PIN / Same Bank]: Validating account via PinExternalTransferService.getAccountInfo");

      try {
        String accountInfoResponse = pinExternalTransferService.getAccountInfo(cleanAccount);

        if (accountInfoResponse == null || accountInfoResponse.contains("\"disabled\"")) {
          throw new IllegalArgumentException("The account validation service (PIN/Same Bank) is not available.");
        }

        Map<String, Object> accountData = gson.fromJson(accountInfoResponse, Map.class);

        if (accountData.containsKey("error") || accountData.containsKey("message") && accountInfoResponse.contains("not found")) {
          throw new IllegalArgumentException("The destination account does not exist in the financial system.");
        }

        String state = String.valueOf(accountData.get("state"));
        String stateDescription = String.valueOf(accountData.get("stateDescription"));

        if (!"1".equals(state) && !"Active".equalsIgnoreCase(stateDescription)) {
          throw new IllegalArgumentException("The destination account exists but is not active (Status: " + stateDescription + ").");
        }

        String holderName = String.valueOf(accountData.get("holder"));
        log.info("PREPARE [PIN / Same Bank]: Account successfully verified. Holder: {}, Bank: {}",
                holderName, accountData.get("entityName"));

      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        log.error("Error validating account via PIN/Same Bank: {}", e.getMessage());
        throw new IllegalArgumentException("Could not verify the existence or status of the account.");
      }

    } else if ("SINPE".equalsIgnoreCase(transferType) || "SINPE_MOVIL".equalsIgnoreCase(transferType)) {
      log.info("PREPARE [SINPE]: Executing specific validation flow for SINPE.");

    } else {
      log.warn("PREPARE: Could not determine the validation channel for account: {} with type: {}",
              destinationAccount, transferType);
    }
  }

  private boolean isSameBankIbanAccount(String accountIdentifier) {
    if (accountIdentifier == null) {
      return false;
    }

    Map<String, String> sinpeProps = getSinpeProperties();
    String bankCode = sinpeProps.getOrDefault("bankCode", "0");

    String cleanAccount = accountIdentifier.replaceAll("\\s+", "").toUpperCase();

    if (cleanAccount.length() >= 8 && cleanAccount.startsWith("CR")) {
      String bankSegment = cleanAccount.substring(4, 8);
      return bankSegment.contains(bankCode);
    }
    return false;
  }

  private void generateAndSendOtpForQuote(AppSelfServiceUser user, String destinationTarget, BigDecimal transferAmount) {
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);

    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SelfServiceRegistration registration = SelfServiceRegistration.instance(
            client, client.getAccountNumber(), client.getFirstname(), client.getMiddlename(), client.getLastname(),
            destinationTarget, user.getEmail(), otp, otp, user.getUsername(), "TRANSFER_OTP",
            SelfServiceRequestType.ACCOUNT_TRANSFER, expiry);

    this.registrationRepository.saveAndFlush(registration);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put("transferAmount", transferAmount != null ? transferAmount.toString() : "N/A");

    this.applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
            this, SelfServiceNotificationEvent.Type.TRANSFER_OTP, user.getId(), user.getFirstname(), user.getLastname(),
            user.getUsername(), user.getEmail(), extractMobile(user), determineMode(user.getEmail(), extractMobile(user)),
            "Unknown IP (Quote Phase)", LocaleContextHolder.getLocale(), contextData));

    log.info("QUOTE: OTP successfully registered and event published for destination target.");
  }

  private void executeCommissionChargeViaSameBank(
          AccountTransferConfirmRequest request,
          BigDecimal feeAmount) {

    log.info("ACCOUNTING CONFIRM: Starting internal fee collection via Fineract CommandWrapper (Multi-tenant).");

    try {
      Map<String, String> config = externalServicePropertiesRepository.getProperties("SELF_SERVICE_COMMISSION_CONFIG");
      
      boolean isTransferFeeEnabled = Boolean.parseBoolean(config.getOrDefault("transfer_fee_enabled", "false"));
      if (!isTransferFeeEnabled) {
          log.info("ACCOUNTING CONFIRM: Fee collection is disabled in the external configuration (c_external_service).");
          return;
      }

      Long toOfficeId = Long.parseLong(config.getOrDefault("to_office_id", "1"));
      Long toClientId = Long.parseLong(config.getOrDefault("to_client_id", "199"));
      Integer toAccountType = Integer.parseInt(config.getOrDefault("to_account_type", "2"));
      
      String toAccountIdStr = "USD".equalsIgnoreCase(request.getCurrencyCode()) 
              ? config.getOrDefault("to_account_id_usd", "140") 
              : config.getOrDefault("to_account_id_crc", "139");
      Long toAccountId = Long.parseLong(toAccountIdStr);

      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      Client client = user.getAppUserClientMappings().iterator().next().getClient();
      Long fromClientId = client.getId();
      Long fromOfficeId = client.getOffice().getId();

      Long internalSavingsAccountId = null;
      try {
        String cleanAccount = request.getFromAccount().replaceAll("\\s+", "");

        if (cleanAccount.length() >= 7) {
          String last7Digits = cleanAccount.substring(cleanAccount.length() - 7);
          log.info("ACCOUNTING CONFIRM: Mapping last 7 digits of IBAN: {}", last7Digits);

          String cleanDigits = last7Digits.replaceFirst("^0+", "");

          if (cleanDigits.isEmpty()) {
            cleanDigits = "0";
          }

          internalSavingsAccountId = Long.valueOf(cleanDigits);
          log.info("ACCOUNTING CONFIRM: Account ID successfully resolved: {}", internalSavingsAccountId);
        } else {
          if (cleanAccount.matches("\\d+")) {
            internalSavingsAccountId = Long.valueOf(cleanAccount);
          }
        }
      } catch (Exception e) {
        log.error("ACCOUNTING CONFIRM: Error processing the extraction of the last 7 digits for account: {}", request.getFromAccount(), e);
      }

      if (internalSavingsAccountId == null) {
        log.warn("ACCOUNTING CONFIRM: Activating default contingency account ID.");        
      }

      Map<String, Object> commandData = new HashMap<>();
      commandData.put("fromOfficeId", fromOfficeId);
      commandData.put("fromClientId", fromClientId);
      commandData.put("fromAccountType", request.getFromAccountType() != null ? request.getFromAccountType() : 2);
      commandData.put("fromAccountId", internalSavingsAccountId);

      commandData.put("toOfficeId", toOfficeId);
      commandData.put("toClientId", toClientId);
      commandData.put("toAccountType", toAccountType);
      commandData.put("toAccountId", toAccountId);

      commandData.put("transferAmount", feeAmount);
      commandData.put("transferDate", request.getTransferDate());
      commandData.put("transferDescription", "Fee Collection Channel " + request.getTransferType());

      commandData.put("locale", request.getLocale() != null ? request.getLocale() : "es");
      commandData.put("dateFormat", request.getDateFormat() != null ? request.getDateFormat() : "dd-MM-yyyy");

      String jsonRequestBody = this.gson.toJson(commandData);

      final CommandWrapper commandRequest = new CommandWrapperBuilder()
              .createAccountTransfer()
              .withJson(jsonRequestBody)
              .build();

      log.info("ACCOUNTING CONFIRM: Executing internal transfer command for fee collection...");
      CommandProcessingResult result = commandsSourceWritePlatformService.logCommandSource(commandRequest);

      if (result != null && result.getResourceId() != null) {
        log.info("ACCOUNTING CONFIRM: Fee successfully collected via internal command. Transaction ID: {}", result.getResourceId());
      } else {
        log.warn("ACCOUNTING CONFIRM: Fee collection command executed but did not return a valid resource ID.");
      }

    } catch (Exception e) {
      log.error("ACCOUNTING CONFIRM: Internal fee collection execution failed: ", e);
    }
  }

  private boolean isAlreadyRegisteredAsBeneficiary(Long appUserId, String destinationAccount) {
    if (destinationAccount == null || destinationAccount.isBlank()) {
      return false;
    }
    try {
      String cleanAccount = destinationAccount.replaceAll("\\s+", "");
      boolean isRegistered = this.tptBeneficiaryReadPlatformService.isBeneficiaryRegistered(appUserId, cleanAccount);
      log.info("BENEFICIARY VALIDATION: Does account {} belong to the beneficiaries of user {}?: {}", cleanAccount, appUserId, isRegistered);
      return isRegistered;
    } catch (Exception e) {
      log.error("BENEFICIARY VALIDATION: Error executing query on m_selfservice_beneficiaries_tpt for account: {}", destinationAccount, e);
      return false;
    }
  }

  private Map<String, String> getSinpeProperties() {
    String sql = "SELECT esp.name, esp.value " +
                 "FROM c_external_service_properties esp " +
                 "JOIN c_external_service es ON esp.external_service_id = es.id " +
                 "WHERE es.name = 'SinpeService'";
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
    Map<String, String> properties = new HashMap<>();
    for (Map<String, Object> row : rows) {
      properties.put((String) row.get("name"), (String) row.get("value"));
    }
    return properties;
  }
}