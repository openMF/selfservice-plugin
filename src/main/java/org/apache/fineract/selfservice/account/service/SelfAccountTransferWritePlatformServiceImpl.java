/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmResponse;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.ResendOtpRequest;
import org.apache.fineract.selfservice.account.data.SameBankTransferCustomData;
import org.apache.fineract.selfservice.account.data.SameBankTransferResponseData;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountForFeesRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountTransferRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAuditRepository;
import org.apache.fineract.selfservice.account.exception.BeneficiaryTransferLimitExceededException;
import org.apache.fineract.selfservice.account.exception.DailyTPTTransactionAmountLimitExceededException;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
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
public class SelfAccountTransferWritePlatformServiceImpl
    implements SelfAccountTransferWritePlatformService {

  private final PlatformSelfServiceSecurityContext context;
  private final AccountTransferQuoteService quoteService;
  private final SinpeExternalApiClient sinpeExternalApiClient;
  private final SelfServiceRegistrationRepository registrationRepository;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;
  private final FromJsonHelper fromApiJsonHelper;
  private final NotificationCooldownCache notificationCooldownCache;

  private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
  private final ExternalIdFactory externalIdFactory;
  private final SelfAccountTransferDataValidator dataValidator;
  private final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  private final ConfigurationDomainService configurationDomainService;
  private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;
  private final ClientReadPlatformService clientReadPlatformService;
  private final OfficeReadPlatformService officeReadPlatformService;
  private final PinExternalTransferService pinExternalTransferService;
  private final SelfServiceAccountForFeesRepository externalServicePropertiesRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Gson gson = new Gson();

  // Injected for external ID resolution
  private final SavingsAccountAssembler savingsAccountAssembler;
  private final LoanAssembler loanAssembler;
  private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;

  // DAO for SAME_BANK transfer audit persistence
  private final SelfServiceSameBankTransferAuditRepository sameBankTransferAuditRepository;

  // Date formatter used for internalRefNumber generation
  private static final DateTimeFormatter REF_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  // Fineract expected full datetime format for internal transfers (prevents future-date validation
  // errors)
  private static final DateTimeFormatter FINERACT_DATETIME_FMT =
      DateTimeFormatter.ofPattern("dd MMMM yyyy");

  private final SavingsAccountTransactionRepository
      savingsAccountTransactionRepository; // inject via constructor

  private final SelfServiceAccountTransferRepository selfServiceAccountTransferRepository; // NEW

  // =====================================================================
  //  PREPARE
  // =====================================================================
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
      throw new IllegalArgumentException(
          "The transfer amount (transferAmount) must be greater than zero.");
    }
    if (toAccount == null || toAccount.isBlank()) {
      throw new IllegalArgumentException(
          "The destination account or number (toAccount) is required.");
    }
    if (fromAccount == null || fromAccount.isBlank()) {
      throw new IllegalArgumentException("The source account (fromAccount) is required.");
    }

    validateDestinationAccount(currentUser.getId(), toAccount, transferType);

    log.info("PREPARE: Validating status and funds of the local source account: {}", fromAccount);

    BigDecimal feeAmount = BigDecimal.ZERO;
    try {
      AccountTransferQuoteResponse quote = this.quoteService.calculateFee(request);
      if (quote != null && quote.getFeeAmount() != null) {
        feeAmount = quote.getFeeAmount();
      }
    } catch (Exception e) {
      log.warn("PREPARE: Could not calculate fee during prepare stage, defaulting to 0", e);
    }

    BigDecimal totalAmount = transferAmount.add(feeAmount);

    Map<String, Object> prepareResponse = new HashMap<>();
    prepareResponse.put("status", "PREPARED");
    prepareResponse.put("fromAccount", fromAccount);
    prepareResponse.put("toAccount", toAccount);
    prepareResponse.put("transferAmount", transferAmount);
    prepareResponse.put("feeAmount", feeAmount);
    prepareResponse.put("totalAmount", totalAmount);
    prepareResponse.put("transferType", transferType);
    prepareResponse.put("currencyCode", currencyCode);
    prepareResponse.put(
        "message",
        "The destination account was verified and the status is suitable to proceed with the quote.");

    log.info(
        "PREPARE: Transfer successfully validated and prepared for destination: {}", toAccount);
    return prepareResponse;
  }

  // =====================================================================
  //  QUOTE
  // =====================================================================
  @Override
  @Transactional
  public Object quoteTransfer(final AccountTransferPrepareRequest request) {
    log.info("QUOTE: Starting quote for channel: {}", request.getTransferType());

    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();

    final AccountTransferQuoteResponse quote = this.quoteService.calculateFee(request);

    log.info("QUOTE: Quote calculated. Triggering new security OTP dispatch.");

    String destinationTarget =
        "SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())
            ? request.getToPhoneNumber()
            : request.getToAccount();

    cleanupOldOtpRegistrations(currentUser);
    releaseOtpCooldown(currentUser);
    generateAndSendOtpForQuote(currentUser, destinationTarget, request.getTransferAmount());

    return this.gson.toJson(quote);
  }

  private void cleanupOldOtpRegistrations(AppSelfServiceUser user) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    try {
      LocalDateTime cutoff = DateUtils.getLocalDateTimeOfSystem().minusMinutes(10);
      int updated =
          registrationRepository.markOldOtpsAsConsumed(
              client.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER, cutoff);
      if (updated > 0) {
        log.info("QUOTE: Cleaned up {} stale OTP records for client {}", updated, client.getId());
      }
    } catch (Exception e) {
      log.warn("Failed to cleanup old OTPs (non-fatal)", e);
    }
  }

  private void releaseOtpCooldown(AppSelfServiceUser user) {
    try {
      String cacheKey = SelfServiceNotificationEvent.Type.TRANSFER_OTP.name() + ":" + user.getId();
      notificationCooldownCache.release(cacheKey);
      log.info("QUOTE: Released OTP cooldown for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to release OTP cooldown (non-fatal)", e);
    }
  }
  
  /**
    * Releases the TRANSFER_SUCCESS notification cooldown for the given user and channel.
    * Called on successful confirm so a legitimate completed transfer always notifies,
    * even if a previous attempt still holds the cooldown entry.
    * Multi-tenant safe: key is scoped by self-service user id (tenant-bound).
    */
   private void releaseTransferSuccessCooldown(AppSelfServiceUser user, String transferType) {
     try {
       String type = StringUtils.isNotBlank(transferType) ? transferType.toUpperCase() : "UNKNOWN";
       String cacheKey = "TRANSFER_SUCCESS:" + user.getId() + ":" + type;
       notificationCooldownCache.release(cacheKey);
       log.info(
           "CONFIRM: Released TRANSFER_SUCCESS cooldown for user {} channel {}",
           user.getId(),
           type);
     } catch (Exception e) {
       log.warn("Failed to release TRANSFER_SUCCESS cooldown (non-fatal)", e);
     }
   }

  // =====================================================================
  //  CONFIRM  (entry-point)
  // =====================================================================
  @Override
  @Transactional
  public Object confirmTransfer(
      AccountTransferConfirmRequest request, HttpServletRequest httpRequest) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    user.validateHasCreatePermission("ACCOUNTTRANSFER");

    validateOtp(request, user);

    BigDecimal feeAmountFromClient =
        request.getFeeAmount() != null ? request.getFeeAmount() : BigDecimal.ZERO;

    log.info(
        "CONFIRM: Starting two-step processing for channel: {} | Fee: {}",
        request.getTransferType(),
        feeAmountFromClient);

    String cleanDestination =
        request.getToAccount() != null ? request.getToAccount().replaceAll("\\s+", "") : "";

    // Route to the correct execution strategy
    if ("PIN".equalsIgnoreCase(request.getTransferType())) {
      return executePinTransfer(request, user, httpRequest);
    } else if ("SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())) {
      return executeSinpeTransfer(request, user, httpRequest);
    }

    Object result;

    if (isSameBankIbanAccount(cleanDestination)
        || "SAME_BANK".equalsIgnoreCase(request.getTransferType())) {
      log.info("CONFIRM -> Internal account detected. Executing local transfer.");
      result = executeInternalTransfer(request, user, httpRequest);
    } else {
      log.info("CONFIRM -> Fallback to internal transfer.");
      result = executeInternalTransfer(request, user, httpRequest);
    }

    if (feeAmountFromClient.compareTo(BigDecimal.ZERO) > 0) {
      log.info(
          "ACCOUNTING CONFIRM: Shifting fee of {} {} to the collector account configured in c_external_service.",
          feeAmountFromClient,
          request.getCurrencyCode());
      executeCommissionChargeViaSameBank(request, feeAmountFromClient);
    }

    return result;
  }

  // =====================================================================
  //  CREATE TRANSFER  (legacy TPT path)
  // =====================================================================
  @Override
  @Transactional
  public CommandProcessingResult createTransfer(
      String type, String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
    Map<String, Object> params = dataValidator.validateCreate(type, apiRequestBodyAsJson);
    if (type.equals("tpt")) {
      checkForLimits(params);
    }

    JsonCommand command = createJsonCommand(apiRequestBodyAsJson);
    CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

    publishTransferEvent(result, params, params, httpRequest);
    return result;
  }

  // =====================================================================
  //  Builds a fully-structured SameBankTransferResponseData instead of
  //  returning the raw CommandProcessingResult.
  // =====================================================================
  private Object executeInternalTransfer(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      HttpServletRequest httpRequest) {

    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    Long fromClientId = client.getId();
    Long fromOfficeId = client.getOffice().getId();

    Long fromAccountId = resolveAccountId(request.getFromAccount(), request.getFromAccountType());
    Long toAccountId = resolveAccountId(request.getToAccount(), request.getToAccountType());

    SavingsAccount fromSavingsAccount =
        savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(fromAccountId);
    SavingsAccount toSavingsAccount =
        savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(toAccountId);

    Long toClientId = toSavingsAccount.getClient().getId();
    Long toOfficeId = toSavingsAccount.getClient().getOffice().getId();

    if (toClientId == null) {
      throw new IllegalArgumentException(
          "Could not determine destination client for internal transfer.");
    }

    // Resolve the currency from the source savings account; fall back to the request
    String resolvedCurrencyCode =
        resolveCurrencyCode(fromSavingsAccount, request.getCurrencyCode());

    // Build Fineract-compatible date
    String transferDateForFineract;
    String localeForFineract = "en";
    String dateFormatForFineract = "dd MMMM yyyy";

    if (StringUtils.isNotBlank(request.getTransferDate())) {
      // Parse client date and append current time to avoid "future date" issues
      try {
        LocalDate clientDate =
            LocalDate.parse(
                request.getTransferDate(),
                DateTimeFormatter.ofPattern(
                    request.getDateFormat() != null ? request.getDateFormat() : "dd-MM-yyyy"));
        LocalDateTime now = DateUtils.getLocalDateTimeOfSystem();
        LocalDateTime transferDateTime = clientDate.atTime(now.toLocalTime());
        transferDateForFineract = transferDateTime.format(FINERACT_DATETIME_FMT);
        Locale defaultLocale = Locale.getDefault();
        localeForFineract = defaultLocale.getLanguage();
      } catch (Exception e) {
        log.warn("Failed to parse client transferDate, falling back to now", e);
        transferDateForFineract =
            DateUtils.getLocalDateTimeOfSystem().format(FINERACT_DATETIME_FMT);
      }
    } else {
      transferDateForFineract = DateUtils.getLocalDateTimeOfSystem().format(FINERACT_DATETIME_FMT);
    }

    // Build the Fineract internal-transfer command
    Map<String, Object> commandData = new HashMap<>();
    commandData.put("fromOfficeId", fromOfficeId);
    commandData.put("fromClientId", fromClientId);
    commandData.put(
        "fromAccountType", request.getFromAccountType() != null ? request.getFromAccountType() : 2);
    commandData.put("fromAccountId", fromAccountId);
    commandData.put("toOfficeId", toOfficeId);
    commandData.put("toClientId", toClientId);
    commandData.put(
        "toAccountType", request.getToAccountType() != null ? request.getToAccountType() : 2);
    commandData.put("toAccountId", toAccountId);
    commandData.put("transferAmount", request.getTransferAmount());
    commandData.put("transferDate", transferDateForFineract); // Full datetime string
    commandData.put(
        "transferDescription",
        request.getTransferDescription() != null
            ? request.getTransferDescription()
            : "Internal Transfer");
    commandData.put("locale", localeForFineract);
    commandData.put("dateFormat", dateFormatForFineract); // Fineract expected format

    String jsonRequestBody = gson.toJson(commandData);

    if (StringUtils.isBlank(jsonRequestBody)) {
      log.error("Failed to serialize command data to JSON. commandData: {}", commandData);
      throw new IllegalArgumentException(
          "Internal error: Failed to serialize transfer command data.");
    }

    log.info("JSON Request Body for Internal Transfer: {}", jsonRequestBody);

    JsonCommand command = createJsonCommand(jsonRequestBody);

    // Capture timestamps before and after the Fineract call
    OffsetDateTime registrationDate = OffsetDateTime.now();

    CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

    log.info("JSON Response Body for Internal Transfer: {}", result.toString());

    OffsetDateTime processingDate = OffsetDateTime.now();

    SavingsAccountTransaction transferTransaction = null;
    OffsetDateTime instant = OffsetDateTime.now();
    String operationId = UUID.randomUUID().toString();
    String description = "Rejected";
    Integer stateCode = 128;

    if (result.getResourceId() != null) {
      transferTransaction =
          savingsAccountTransactionRepository.findById(result.getResourceId()).orElse(null);
      log.info("Transfer created with id: {}. ", result.getResourceId());
      instant =
          selfServiceAccountTransferRepository.findCreatedOnUtcByTransferId(result.getResourceId());
      log.info("Fetching created_on_utc: {} ", instant);
      processingDate = instant;
      log.info("Fetching created_on_tz: {} ", processingDate);
      String refNo = transferTransaction.getRefNo();
      log.info("Fetching RefNo: {} ", operationId);
      if (refNo != null) {
        operationId = refNo;
      }
      description = "Completed";
      stateCode = 32;
    }

    log.info("Build the structured SAME_BANK response");
    BigDecimal feeAmount =
        request.getFeeAmount() != null ? request.getFeeAmount() : BigDecimal.ZERO;
    BigDecimal transferAmount = request.getTransferAmount();
    BigDecimal totalAmount = transferAmount.add(feeAmount);

    String internalRefNumber =
        generateInternalRefNumber(processingDate, fromOfficeId, result.getResourceId());

    SameBankTransferCustomData customData =
        SameBankTransferCustomData.builder()
            .totalAmount(totalAmount.toPlainString())
            .transferDescription(
                StringUtils.isNotBlank(request.getTransferDescription())
                    ? request.getTransferDescription()
                    : "Internal Transfer")
            .feeAmount(feeAmount.toPlainString())
            .debitAmount(transferAmount.toPlainString())
            .exchangeRateAmount("1")
            .build();

    SameBankTransferResponseData responseData =
        SameBankTransferResponseData.builder()
            .commissionAmount(feeAmount)
            .commissionCurrency(resolvedCurrencyCode)
            .customData(customData)
            .debitCurrencyCode(resolvedCurrencyCode)
            .debitedAmount(transferAmount)
            .exchangeRate(BigDecimal.ZERO)
            .operationId(operationId)
            .processingDate(processingDate.toString())
            .registrationDate(registrationDate.toString())
            .rejectDescription("")
            .internalRefNumber(internalRefNumber)
            .stateDescription(description)
            .stateCode(stateCode)
            .successful(true)
            .build();

    log.info("Persist the audit trail");
    persistSameBankTransferAudit(
        client.getId(),
        fromAccountId,
        toAccountId,
        request.getFromAccount(),
        request.getToAccount(),
        transferAmount,
        feeAmount,
        resolvedCurrencyCode,
        operationId,
        internalRefNumber,
        result.getResourceId(),
        request.getTransferDescription(),
        request.getReference(),
        description,
        true,
        "",
        registrationDate,
        processingDate);

    log.info("Homologating SAME_BANK response structure");
    Map<String, Object> rawInternalMap = gson.fromJson(gson.toJson(responseData), Map.class);
    Map<String, Object> homologatedData =
        homologateResponseData(rawInternalMap, request.getTransferAmount(), resolvedCurrencyCode);

    log.info("Wrap in the generic confirm-response envelope");
    AccountTransferConfirmResponse wrappedResponse =
        AccountTransferConfirmResponse.builder()
            .transferType("SAME_BANK")
            .data(homologatedData)
            .build();
    releaseTransferSuccessCooldown(user, "SAME_BANK");
    publishFastPaymentTransferEvent(result, request, httpRequest);

    log.info(
        "CONFIRM SAME_BANK: Transfer completed. operationId={}, internalRefNumber={}, fineractTransferId={}",
        operationId,
        internalRefNumber,
        result.getResourceId());

    return wrappedResponse;
  }

  // =====================================================================
  // Helper: resolve currency code from SavingsAccount
  // =====================================================================
  private String resolveCurrencyCode(SavingsAccount savingsAccount, String fallbackCurrencyCode) {
    try {
      if (savingsAccount != null
          && savingsAccount.getCurrency() != null
          && StringUtils.isNotBlank(savingsAccount.getCurrency().getCode())) {
        return savingsAccount.getCurrency().getCode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve currency from SavingsAccount, falling back to request value", e);
    }
    return StringUtils.isNotBlank(fallbackCurrencyCode) ? fallbackCurrencyCode : "CRC";
  }

  // =====================================================================
  //  Helper: generate the internal reference number
  //  Format: YYYYMMDD + officeId (5 digits, zero-padded) + resourceId (12 digits, zero-padded)
  //  Example: 2026072237383000000001040
  // =====================================================================
  private String generateInternalRefNumber(
      OffsetDateTime dateTime, Long officeId, Long resourceId) {
    String datePart = dateTime.format(REF_DATE_FMT);
    String officePart = String.format("%05d", officeId != null ? officeId : 0L);
    String resourcePart = String.format("%012d", resourceId != null ? resourceId : 0L);
    return datePart + officePart + resourcePart;
  }

  // =====================================================================
  // Helper: persist the SAME_BANK audit record
  // =====================================================================
  private void persistSameBankTransferAudit(
      Long clientId,
      Long fromAccountId,
      Long toAccountId,
      String fromAccountIdentifier,
      String toAccountIdentifier,
      BigDecimal transferAmount,
      BigDecimal feeAmount,
      String currencyCode,
      String operationId,
      String internalRefNumber,
      Long fineractTransferId,
      String transferDescription,
      String reference,
      String stateDescription,
      boolean successful,
      String rejectDescription,
      OffsetDateTime registrationDate,
      OffsetDateTime processingDate) {
    try {
      SelfServiceSameBankTransferAudit audit =
          SelfServiceSameBankTransferAudit.instance(
              clientId,
              fromAccountId,
              toAccountId,
              fromAccountIdentifier,
              toAccountIdentifier,
              transferAmount,
              feeAmount,
              currencyCode,
              operationId,
              internalRefNumber,
              fineractTransferId,
              transferDescription,
              reference,
              stateDescription,
              successful,
              rejectDescription,
              registrationDate,
              processingDate);

      sameBankTransferAuditRepository.saveAndFlush(audit);
      log.info(
          "SAME_BANK audit persisted: operationId={}, internalRefNumber={}",
          operationId,
          internalRefNumber);
    } catch (Exception e) {
      // Audit failure must NOT roll back the actual transfer
      log.error(
          "Failed to persist SAME_BANK transfer audit (non-fatal): operationId={}", operationId, e);
    }
  }

  // =====================================================================
  //  PIN TRANSFER
  // =====================================================================
  private Object executePinTransfer(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      HttpServletRequest httpRequest) {
    log.info(
        "CONFIRM PIN: Starting PIN flow with strict destination and origin metadata validation.");

    try {
      Client client = user.getAppUserClientMappings().iterator().next().getClient();

      boolean yaEsBeneficiario =
          this.isAlreadyRegisteredAsBeneficiary(user.getId(), request.getToAccount());
      if (yaEsBeneficiario) {
        log.warn(
            "CONFIRM PIN: The destination account {} is already registered in beneficiaries.",
            request.getToAccount());
      }

      String destinationName = null;
      String destinationId = null;
      String destinationIdType = null;
      String dynamicCurrencyCode = null;

      try {
        log.info("CONFIRM PIN: Invoking getAccountInfo to resolve destination IBAN metadata.");
        String infoJsonResponse =
            this.pinExternalTransferService.getAccountInfo(request.getToAccount());

        if (infoJsonResponse != null
            && !infoJsonResponse.contains("\"disabled\"")
            && !infoJsonResponse.contains("\"error\"")) {
          Map<String, Object> infoMap = this.gson.fromJson(infoJsonResponse, Map.class);

          if (infoMap != null) {
            if (infoMap.get("holder") != null) destinationName = infoMap.get("holder").toString();
            if (infoMap.get("holderId") != null) destinationId = infoMap.get("holderId").toString();
            if (infoMap.get("currencyCode") != null)
              dynamicCurrencyCode = infoMap.get("currencyCode").toString();

            if (infoMap.get("holderIdType") != null) {
              Double idTypeDouble = Double.parseDouble(infoMap.get("holderIdType").toString());
              destinationIdType = String.valueOf(idTypeDouble.intValue());
            }
          }
        }
      } catch (Exception e) {
        log.error("CONFIRM PIN: Error querying account info on the external gateway: ", e);
        throw new IllegalArgumentException(
            "Destination account data could not be verified. Please try again later.");
      }

      if (StringUtils.isBlank(destinationName) || StringUtils.isBlank(dynamicCurrencyCode)) {
        log.error(
            "CONFIRM PIN: Aborting transfer. Incomplete destination data. Holder: {}, Currency: {}",
            destinationName,
            dynamicCurrencyCode);
        throw new IllegalArgumentException(
            "The destination account did not return valid holder or currency information. Transfer canceled.");
      }

      String originName = null;
      if (client != null) {
        if (StringUtils.isNotBlank(client.getDisplayName())) {
          originName = client.getDisplayName();
        } else if (StringUtils.isNotBlank(client.getFullname())) {
          originName = client.getFullname();
        } else {
          StringBuilder sb = new StringBuilder();
          if (StringUtils.isNotBlank(client.getFirstname()))
            sb.append(client.getFirstname().trim());
          if (StringUtils.isNotBlank(client.getMiddlename()))
            sb.append(" ").append(client.getMiddlename().trim());
          if (StringUtils.isNotBlank(client.getLastname()))
            sb.append(" ").append(client.getLastname().trim());
          originName = sb.toString().trim();
        }
      }

      if (StringUtils.isBlank(originName)) {
        log.error(
            "CONFIRM PIN: Aborting transfer. Could not determine the origin client name in Fineract.");
        throw new IllegalArgumentException(
            "Origin client identity could not be verified. Transfer canceled to avoid external rejections.");
      }

      org.apache.fineract.selfservice.account.data.PinTransferRequest pinRequest =
          new org.apache.fineract.selfservice.account.data.PinTransferRequest();
      pinRequest.setAmount(request.getTransferAmount());
      pinRequest.setCurrency(dynamicCurrencyCode);
      pinRequest.setDescription(
          StringUtils.isNotBlank(request.getTransferDescription())
              ? request.getTransferDescription()
              : "PIN Transfer");
      pinRequest.setOriginCustomerName(originName);
      pinRequest.setOriginIban(request.getFromAccount().replaceAll("\\s+", ""));
      pinRequest.setOriginCustomerId(
          client.getExternalId() != null
              ? client.getExternalId().getValue()
              : client.getAccountNumber());
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
      pinRequest.setReference(
          StringUtils.isNotBlank(request.getReference()) ? request.getReference() : "Ref-PIN");
      pinRequest.setDebitIban(true);

      log.info(
          "CONFIRM PIN: Data successfully validated. Dispatching funds to the external gateway...");
      String pinServiceResponse = this.pinExternalTransferService.executePinTransfer(pinRequest);

      if (pinServiceResponse != null
          && (pinServiceResponse.contains("\"disabled\"")
              || pinServiceResponse.contains("\"error\""))) {
        throw new IllegalArgumentException("The external PIN gateway rejected the transaction.");
      }

      log.info("CONFIRM PIN: Successfully processed and debited by the external service.");

      Map<String, Object> externalData = gson.fromJson(pinServiceResponse, Map.class);

      Map<String, Object> homologatedData =
          homologateResponseData(externalData, request.getTransferAmount(), dynamicCurrencyCode);

      Map<String, Object> response = new HashMap<>();
      response.put("transferType", "PIN");
      response.put("data", homologatedData);
      releaseTransferSuccessCooldown(user, "PIN");
      publishPinTransferEvent(request, user, httpRequest, externalData);

      return response;

    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      log.error("CONFIRM PIN: Unexpected critical error executing PIN transfer: ", e);
      throw new RuntimeException("Error processing external PIN transfer.", e);
    }
  }

  // =====================================================================
  //  SINPE TRANSFER
  // =====================================================================
  private Object executeSinpeTransfer(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      HttpServletRequest httpRequest) {
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SinpeTransferRequest sinpeRequest =
        SinpeTransferRequest.builder()
            .originCustomerId(
                client.getExternalId() != null
                    ? client.getExternalId().getValue()
                    : client.getAccountNumber())
            .originCustomerName(client.getDisplayName())
            .originIban(request.getFromAccount())
            .destinationPhone(request.getToAccount())
            .amount(request.getTransferAmount())
            .currencyCode("CRC")
            .description(request.getTransferDescription())
            .debitIBAN(true)
            .customData(List.of(new SinpeTransferRequest.CustomData("Source", "SelfServiceApp")))
            .build();

    log.info("CONFIRM SINPE_MOVIL: Dispatching funds to the external SINPE gateway...");

    Object sinpeResponse = sinpeExternalApiClient.transferToPhone(sinpeRequest);
    String sinpeServiceResponse =
        sinpeResponse instanceof String ? (String) sinpeResponse : gson.toJson(sinpeResponse);

    if (sinpeServiceResponse != null
        && (sinpeServiceResponse.contains("\"disabled\"")
            || sinpeServiceResponse.contains("\"error\"")
            || sinpeServiceResponse.contains("\"successful\":false"))) {
      log.error(
          "CONFIRM SINPE_MOVIL: External gateway rejected the transaction. Response: {}",
          sinpeServiceResponse);
      throw new IllegalArgumentException("The external SINPE gateway rejected the transaction.");
    }

    log.info("CONFIRM SINPE_MOVIL: Successfully processed by the external service.");

    Map<String, Object> externalData = gson.fromJson(sinpeServiceResponse, Map.class);

    Map<String, Object> homologatedData =
        homologateResponseData(externalData, request.getTransferAmount(), "CRC");

    Map<String, Object> response = new HashMap<>();
    response.put("transferType", "SINPE_MOVIL");
    response.put("data", homologatedData);
    releaseTransferSuccessCooldown(user, "SINPE_MOVIL");
    publishSinpeTransferEvent(request, user, httpRequest, externalData);

    return response;
  }

  // =====================================================================
  //  NOTIFICATION PUBLISHERS
  // =====================================================================
  private void publishFastPaymentTransferEvent(
      CommandProcessingResult result,
      AccountTransferConfirmRequest request,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      String transferType =
        StringUtils.isNotBlank(request.getTransferType())
            ? request.getTransferType().toUpperCase()
            : "SAME_BANK";
      releaseTransferSuccessCooldown(user, transferType);
      String cacheKey = "TRANSFER_SUCCESS:" + user.getId() + ":" + request.getTransferType();
      if (!notificationCooldownCache.tryAcquire(cacheKey)) {
        log.warn(
            "CONFIRM: Notification cooldown active for user {}, skipping duplicate {} success notification.",
            user.getId(),
            request.getTransferType());
        return;
      }
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put(
          "transferDescription",
          StringUtils.isNotBlank(request.getTransferDescription())
              ? request.getTransferDescription()
              : "N/A");
      contextData.put(
          "fromAccountNumber",
          StringUtils.isNotBlank(request.getFromAccount()) ? request.getFromAccount() : "N/A");
      contextData.put(
          "toAccountNumber",
          StringUtils.isNotBlank(request.getToAccount())
              ? request.getToAccount()
              : (StringUtils.isNotBlank(request.getToPhoneNumber())
                  ? request.getToPhoneNumber()
                  : "N/A"));
      contextData.put(
          "transferId",
          result != null && result.getResourceId() != null
              ? result.getResourceId().toString()
              : "N/A");
      contextData.put(
          "transactionDate",
          StringUtils.isNotBlank(request.getTransferDate()) ? request.getTransferDate() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      try {
        Client client = user.getAppUserClientMappings().iterator().next().getClient();
        contextData.put(
            "fromClientName",
            StringUtils.isNotBlank(client.getDisplayName()) ? client.getDisplayName() : "N/A");
      } catch (Exception e) {
        contextData.put("fromClientName", "N/A");
      }

      contextData.put("toClientName", "N/A");
      contextData.put("fromOfficeName", "N/A");
      contextData.put("toOfficeName", "N/A");

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
              ipAddress,
              LocaleContextHolder.getLocale(),
              contextData));
    } catch (Exception e) {
      log.warn("Failed to publish transfer notification event", e);
    }
  }

  private void publishSinpeTransferEvent(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      HttpServletRequest httpRequest,
      Map<String, Object> externalData) {
    try {
        releaseTransferSuccessCooldown(user, "SINPE_MOVIL");
      String cacheKey = "TRANSFER_SUCCESS:" + user.getId() + ":SINPE_MOVIL";
      if (!notificationCooldownCache.tryAcquire(cacheKey)) {
        log.warn(
            "CONFIRM SINPE_MOVIL: Notification cooldown active for user {}, skipping duplicate SINPE_MOVIL success notification.",
            user.getId());
        return;
      }
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put(
          "transferDescription",
          StringUtils.isNotBlank(request.getTransferDescription())
              ? request.getTransferDescription()
              : "N/A");
      contextData.put(
          "fromAccountNumber",
          StringUtils.isNotBlank(request.getFromAccount()) ? request.getFromAccount() : "N/A");
      contextData.put(
          "toAccountNumber",
          StringUtils.isNotBlank(request.getToAccount()) ? request.getToAccount() : "N/A");

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
      contextData.put(
          "transactionDate",
          StringUtils.isNotBlank(request.getTransferDate()) ? request.getTransferDate() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      try {
        Client client = user.getAppUserClientMappings().iterator().next().getClient();
        contextData.put(
            "fromClientName",
            StringUtils.isNotBlank(client.getDisplayName()) ? client.getDisplayName() : "N/A");
      } catch (Exception e) {
        contextData.put("fromClientName", "N/A");
      }

      contextData.put("toClientName", "N/A");
      contextData.put("fromOfficeName", "N/A");
      contextData.put("toOfficeName", "N/A");

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
              ipAddress,
              LocaleContextHolder.getLocale(),
              contextData));

      log.info(
          "CONFIRM SINPE_MOVIL: Notification event published successfully for user {}",
          user.getId());
    } catch (Exception e) {
      log.warn("Failed to publish SINPE_MOVIL transfer notification event", e);
    }
  }

  private void publishPinTransferEvent(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      HttpServletRequest httpRequest,
      Map<String, Object> externalData) {
    try {
        // Invalidate any prior cooldown so a successful PIN confirm always notifies
    releaseTransferSuccessCooldown(user, "PIN");
      String cacheKey = "TRANSFER_SUCCESS:" + user.getId() + ":PIN";
      if (!notificationCooldownCache.tryAcquire(cacheKey)) {
        log.warn(
            "CONFIRM PIN: Notification cooldown active for user {}, skipping duplicate PIN success notification.",
            user.getId());
        return;
      }
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put(
          "transferDescription",
          StringUtils.isNotBlank(request.getTransferDescription())
              ? request.getTransferDescription()
              : "N/A");
      contextData.put(
          "fromAccountNumber",
          StringUtils.isNotBlank(request.getFromAccount()) ? request.getFromAccount() : "N/A");
      contextData.put(
          "toAccountNumber",
          StringUtils.isNotBlank(request.getToAccount()) ? request.getToAccount() : "N/A");

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
      contextData.put(
          "transactionDate",
          StringUtils.isNotBlank(request.getTransferDate()) ? request.getTransferDate() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      try {
        Client client = user.getAppUserClientMappings().iterator().next().getClient();
        contextData.put(
            "fromClientName",
            StringUtils.isNotBlank(client.getDisplayName()) ? client.getDisplayName() : "N/A");
      } catch (Exception e) {
        contextData.put("fromClientName", "N/A");
      }

      contextData.put("toClientName", "N/A");
      contextData.put("fromOfficeName", "N/A");
      contextData.put("toOfficeName", "N/A");

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
              ipAddress,
              LocaleContextHolder.getLocale(),
              contextData));

      log.info("CONFIRM PIN: Notification event published successfully for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to publish PIN transfer notification event", e);
    }
  }

  private void publishTransferEvent(
      CommandProcessingResult result,
      Map<String, Object> params,
      Map<String, Object> originalParams,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          getFieldValue(params, originalParams, "transactionAmount", "transferAmount", "amount"));
      contextData.put(
          "transferDescription",
          getFieldValue(params, originalParams, "transferDescription", "description"));
      contextData.put(
          "transactionDate",
          getFieldValue(params, originalParams, "transactionDate", "transferDate"));
      contextData.put(
          "fromAccountNumber",
          getFieldValue(params, originalParams, "fromAccountNumber", "fromAccountId"));
      contextData.put(
          "toAccountNumber",
          getFieldValue(params, originalParams, "toAccountNumber", "toAccountId"));
      contextData.put(
          "transferId", result.getResourceId() != null ? result.getResourceId().toString() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      resolveClientAndOfficeNames(contextData, params, originalParams);

      log.debug("Publishing transfer notification with contextData: {}", contextData);

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
              ipAddress,
              LocaleContextHolder.getLocale(),
              contextData));
    } catch (Exception e) {
      log.error("Failed to publish transfer notification event", e);
    }
  }

  // =====================================================================
  //  PRIVATE HELPERS
  // =====================================================================
  private JsonCommand createJsonCommand(String json) {
    if (StringUtils.isBlank(json)) {
      throw new IllegalArgumentException("JSON request body cannot be blank");
    }
    JsonElement parsed = fromApiJsonHelper.parse(json);
    return JsonCommand.from(
        json,
        parsed,
        fromApiJsonHelper,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
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
            request.getFromAccount() != null ? request.getFromAccount() : "N/A",
            user.getEmail(),
            otp,
            otp,
            user.getUsername(),
            "TRANSFER_OTP",
            SelfServiceRequestType.ACCOUNT_TRANSFER,
            expiry);

    registrationRepository.saveAndFlush(registration);
    registration.markDispatched(); // <-- ensure dispatch metadata is set
    registrationRepository.saveAndFlush(registration);

    log.info(
        "OTP GENERATED: registrationId={}, clientId={}, expiresAt={}, token={}",
        registration.getId(),
        client.getId(),
        expiry,
        otp);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put(
        "transferAmount",
        request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
    contextData.put("resend", true); // marker for templates if needed

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

    log.info(
        "OTP EVENT PUBLISHED: type=TRANSFER_OTP, userId={}, registrationId={}",
        user.getId(),
        registration.getId());

    Map<String, Object> response = new HashMap<>();
    response.put("status", "AWAITING_OTP");
    response.put("message", "OTP sent successfully. Please check your SMS or Email.");
    response.put("expiresAt", expiry);
    response.put("otpId", registration.getId());
    return gson.toJson(response);
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

  private void resolveClientAndOfficeNames(
      Map<String, Object> contextData,
      Map<String, Object> params,
      Map<String, Object> originalParams) {
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
                String fromOfficeName =
                    officeReadPlatformService.retrieveOffice(fromClient.getOfficeId()).getName();
                if (StringUtils.isNotBlank(fromOfficeName)) {
                  contextData.put("fromOfficeName", fromOfficeName);
                }
              } catch (Exception e) {
                log.debug(
                    "Could not fetch fromOfficeName for officeId: {}", fromClient.getOfficeId());
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
                String toOfficeName =
                    officeReadPlatformService.retrieveOffice(toClient.getOfficeId()).getName();
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

  private Object getFieldValue(
      Map<String, Object> params, Map<String, Object> originalParams, String... possibleKeys) {
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

  private Long getLongValue(
      Map<String, Object> params, Map<String, Object> originalParams, String key) {
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
    Long transferLimit =
        tptBeneficiaryReadPlatformService.getTransferLimit(
            user.getId(), toAccount.getAccountId(), toAccount.getAccountType());
    if (transferLimit != null
        && transferLimit > 0
        && transactionAmount.compareTo(new BigDecimal(transferLimit)) > 0) {
      throw new BeneficiaryTransferLimitExceededException();
    }

    if (configurationDomainService.isDailyTPTLimitEnabled()) {
      Long dailyTPTLimit = configurationDomainService.getDailyTPTLimit();
      if (dailyTPTLimit != null && dailyTPTLimit > 0) {
        BigDecimal dailyTPTLimitBD = new BigDecimal(dailyTPTLimit);
        BigDecimal totTransactionAmount =
            accountTransfersReadPlatformService.getTotalTransactionAmount(
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

  private void validateDestinationAccount(
      Long appUserId, String destinationAccount, String transferType) {
    boolean isBeneficiaryActive = isAlreadyRegisteredAsBeneficiary(appUserId, destinationAccount);

    if (isBeneficiaryActive) {
      log.info(
          "PREPARE: The destination account {} is already registered and active as a beneficiary.",
          destinationAccount);
      return;
    }

    log.info(
        "PREPARE: Destination account not previously registered. Evaluating channel for: {}",
        transferType);

    String cleanAccount = destinationAccount.replaceAll("\\s+", "");

    if ("SAME_BANK".equalsIgnoreCase(transferType)) {
      log.info(
          "PREPARE [SAME_BANK]: Destination is an internal account. Validation will be performed via external ID resolution during execution.");
    } else if ("PIN".equalsIgnoreCase(transferType) || isSameBankIbanAccount(cleanAccount)) {
      log.info(
          "PREPARE [PIN / Same Bank IBAN]: Validating account via PinExternalTransferService.getAccountInfo");
      try {
        String accountInfoResponse = pinExternalTransferService.getAccountInfo(cleanAccount);

        if (accountInfoResponse == null || accountInfoResponse.contains("\"disabled\"")) {
          throw new IllegalArgumentException(
              "The account validation service (PIN/Same Bank) is not available.");
        }

        Map<String, Object> accountData = gson.fromJson(accountInfoResponse, Map.class);

        if (accountData.containsKey("error")
            || (accountData.containsKey("message") && accountInfoResponse.contains("not found"))) {
          throw new IllegalArgumentException(
              "The destination account does not exist in the financial system.");
        }

        String state = String.valueOf(accountData.get("state"));
        String stateDescription = String.valueOf(accountData.get("stateDescription"));

        if (!"1".equals(state) && !"Active".equalsIgnoreCase(stateDescription)) {
          throw new IllegalArgumentException(
              "The destination account exists but is not active (Status: "
                  + stateDescription
                  + ").");
        }

        String holderName = String.valueOf(accountData.get("holder"));
        log.info(
            "PREPARE [PIN / Same Bank]: Account successfully verified. Holder: {}, Bank: {}",
            holderName,
            accountData.get("entityName"));

      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        log.error("Error validating account via PIN/Same Bank: {}", e.getMessage());
        throw new IllegalArgumentException(
            "Could not verify the existence or status of the account.");
      }
    } else if ("SINPE".equalsIgnoreCase(transferType)
        || "SINPE_MOVIL".equalsIgnoreCase(transferType)) {
      log.info("PREPARE [SINPE]: Executing specific validation flow for SINPE.");
    } else {
      log.warn(
          "PREPARE: Could not determine the validation channel for account: {} with type: {}",
          destinationAccount,
          transferType);
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

  private void generateAndSendOtpForQuote(
      AppSelfServiceUser user, String destinationTarget, BigDecimal transferAmount) {
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
            destinationTarget,
            user.getEmail(),
            otp,
            otp,
            user.getUsername(),
            "TRANSFER_OTP",
            SelfServiceRequestType.ACCOUNT_TRANSFER,
            expiry);

    this.registrationRepository.saveAndFlush(registration);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put("transferAmount", transferAmount != null ? transferAmount.toString() : "N/A");

    this.applicationEventPublisher.publishEvent(
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
            "Unknown IP (Quote Phase)",
            LocaleContextHolder.getLocale(),
            contextData));

    log.info("QUOTE: OTP successfully registered and event published for destination target.");
  }

  private void executeCommissionChargeViaSameBank(
      AccountTransferConfirmRequest request, BigDecimal feeAmount) {
    log.info(
        "ACCOUNTING CONFIRM: Starting internal fee collection via Fineract Service (Multi-tenant).");

    try {
      Map<String, String> config =
          externalServicePropertiesRepository.getProperties("SELF_SERVICE_COMMISSION_CONFIG");

      boolean isTransferFeeEnabled =
          Boolean.parseBoolean(config.getOrDefault("transfer_fee_enabled", "false"));
      if (!isTransferFeeEnabled) {
        log.info(
            "ACCOUNTING CONFIRM: Fee collection is disabled in the external configuration (c_external_service).");
        return;
      }

      Long toOfficeId = Long.parseLong(config.getOrDefault("to_office_id", "1"));
      Long toClientId = Long.parseLong(config.getOrDefault("to_client_id", "199"));
      Integer toAccountType = Integer.parseInt(config.getOrDefault("to_account_type", "2"));

      String toAccountIdStr =
          "USD".equalsIgnoreCase(request.getCurrencyCode())
              ? config.getOrDefault("to_account_id_usd", "140")
              : config.getOrDefault("to_account_id_crc", "139");
      Long toAccountId = Long.parseLong(toAccountIdStr);

      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      Client client = user.getAppUserClientMappings().iterator().next().getClient();
      Long fromClientId = client.getId();
      Long fromOfficeId = client.getOffice().getId();

      Long fromAccountId =
          resolveAccountId(
              request.getFromAccount(),
              request.getFromAccountType() != null ? request.getFromAccountType() : 2);

      Map<String, Object> commandData = new HashMap<>();
      commandData.put("fromOfficeId", fromOfficeId);
      commandData.put("fromClientId", fromClientId);
      commandData.put(
          "fromAccountType",
          request.getFromAccountType() != null ? request.getFromAccountType() : 2);
      commandData.put("fromAccountId", fromAccountId);
      commandData.put("toOfficeId", toOfficeId);
      commandData.put("toClientId", toClientId);
      commandData.put("toAccountType", toAccountType);
      commandData.put("toAccountId", toAccountId);
      commandData.put("transferAmount", feeAmount);
      commandData.put("transferDate", request.getTransferDate());
      commandData.put("transferDescription", "Fee Collection Channel " + request.getTransferType());
      commandData.put("locale", request.getLocale() != null ? request.getLocale() : "es");
      commandData.put(
          "dateFormat", request.getDateFormat() != null ? request.getDateFormat() : "dd-MM-yyyy");

      String jsonRequestBody = this.gson.toJson(commandData);

      if (StringUtils.isBlank(jsonRequestBody)) {
        log.error("Failed to serialize command data to JSON. commandData: {}", commandData);
        throw new IllegalArgumentException(
            "Internal error: Failed to serialize transfer command data.");
      }

      JsonCommand command = createJsonCommand(jsonRequestBody);
      log.info("ACCOUNTING CONFIRM: Executing internal transfer command for fee collection...");
      CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

      if (result != null && result.getResourceId() != null) {
        log.info(
            "ACCOUNTING CONFIRM: Fee successfully collected via internal command. Transaction ID: {}",
            result.getResourceId());
      } else {
        log.warn(
            "ACCOUNTING CONFIRM: Fee collection command executed but did not return a valid resource ID.");
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
      boolean isRegistered =
          this.tptBeneficiaryReadPlatformService.isBeneficiaryRegistered(appUserId, cleanAccount);
      log.info(
          "BENEFICIARY VALIDATION: Does account {} belong to the beneficiaries of user {}?: {}",
          cleanAccount,
          appUserId,
          isRegistered);
      return isRegistered;
    } catch (Exception e) {
      log.error(
          "BENEFICIARY VALIDATION: Error executing query on m_selfservice_beneficiaries_tpt for account: {}",
          destinationAccount,
          e);
      return false;
    }
  }

  private Map<String, String> getSinpeProperties() {
    String sql =
        "SELECT esp.name, esp.value FROM c_external_service_properties esp JOIN c_external_service es ON esp.external_service_id = es.id WHERE es.name = 'SinpeService'";
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
    Map<String, String> properties = new HashMap<>();
    for (Map<String, Object> row : rows) {
      properties.put((String) row.get("name"), (String) row.get("value"));
    }
    return properties;
  }

  private Long resolveAccountId(String accountIdentifier, Integer accountType) {
    if (StringUtils.isBlank(accountIdentifier)) {
      throw new IllegalArgumentException("Account identifier cannot be null or blank.");
    }

    String trimmed = accountIdentifier.trim();
    log.debug("Resolving account identifier: {}", trimmed);

    try {
      Long numericId = Long.valueOf(trimmed);
      log.debug("Parsed as numeric ID: {}", numericId);
      return resolveNumericAccountId(numericId, accountType);
    } catch (NumberFormatException e) {
      log.debug("Not a numeric ID, treating as external ID / IBAN");
    }

    PortfolioAccountType type = PortfolioAccountType.fromInt(accountType != null ? accountType : 2);
    org.apache.fineract.infrastructure.core.domain.ExternalId externalId =
        externalIdFactory.create(trimmed);

    if (type == PortfolioAccountType.SAVINGS) {
      Long accountId = savingsAccountRepositoryWrapper.findIdByExternalId(externalId);
      SavingsAccount savingsAccount =
          savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId);
      if (savingsAccount == null) {
        throw new IllegalArgumentException("Savings account not found for external ID: " + trimmed);
      }
      log.info(
          "Resolved savings account externalId={} -> internalId={}",
          trimmed,
          savingsAccount.getId());
      return savingsAccount.getId();
    } else if (type == PortfolioAccountType.LOAN) {
      var loan = loanAssembler.assembleFrom(externalId);
      log.info("Resolved loan externalId={} -> internalId={}", trimmed, loan.getId());
      return loan.getId();
    }

    throw new IllegalArgumentException(
        "Unsupported account type: " + accountType + " for identifier: " + trimmed);
  }

  private Long resolveNumericAccountId(Long numericId, Integer accountType) {
    PortfolioAccountType type = PortfolioAccountType.fromInt(accountType != null ? accountType : 2);
    if (type == PortfolioAccountType.SAVINGS) {
      return savingsAccountAssembler.assembleFrom(numericId, false).getId();
    } else if (type == PortfolioAccountType.LOAN) {
      return loanAssembler.assembleFrom(numericId).getId();
    }
    throw new IllegalArgumentException("Unsupported numeric account type: " + accountType);
  }

  // =====================================================================
  //  RESEND OTP
  // =====================================================================
  @Override
  @Transactional
  public Object resendTransferOtp(ResendOtpRequest resendRequest, HttpServletRequest httpRequest) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    LocalDateTime now = DateUtils.getLocalDateTimeOfSystem();

    log.info("RESEND OTP: Starting for userId={}, clientId={}", user.getId(), client.getId());
    // 1. Expire ALL existing non-consumed OTPs for this client + request type
    List<SelfServiceRegistration> activeOtps =
        registrationRepository.findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            client.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER);

    if (!activeOtps.isEmpty()) {
      log.info(
          "RESEND OTP: Found {} active OTP(s) – marking them expired/consumed", activeOtps.size());
      for (SelfServiceRegistration reg : activeOtps) {
        markAsExpired(reg);
      }
    } else if (activeOtps.isEmpty()) {
      log.info("RESEND OTP: No active OTP found → generating fresh one.");
      return generateNewOtpForResend(user, resendRequest, httpRequest);
    }

    // 2. Force-release the notification cooldown (same key used by quote)
    releaseOtpCooldown(user);

    // 3. Always generate a brand-new OTP and publish the notification event
    Object result = generateNewOtpForResend(user, resendRequest, httpRequest);

    log.info("RESEND OTP: Completed for userId={}", user.getId());
    return result;
  }

  private Object generateNewOtpForResend(
      AppSelfServiceUser user, ResendOtpRequest resendRequest, HttpServletRequest httpRequest) {
    AccountTransferConfirmRequest dummy = new AccountTransferConfirmRequest();
    dummy.setFromAccount(resendRequest.getFromAccount());
    dummy.setToAccount(resendRequest.getToAccount());
    dummy.setTransferType(resendRequest.getTransferType());
    dummy.setTransferDescription(resendRequest.getTransferDescription());
    dummy.setTransferAmount(BigDecimal.ZERO);
    // Re-use the proven path that already works for /quote
    return generateAndSendOtp(dummy, user, httpRequest);
  }

  private void markAsExpired(SelfServiceRegistration reg) {
    reg.setExpiresAt(DateUtils.getLocalDateTimeOfSystem().minusSeconds(1));
    reg.markConsumed();
    registrationRepository.saveAndFlush(reg);
    log.info("Marked OTP registration {} as expired/consumed", reg.getId());
  }

  private Map<String, Object> homologateResponseData(
      Map<String, Object> rawData, BigDecimal fallbackAmount, String fallbackCurrency) {
    if (rawData == null) {
      rawData = new HashMap<>();
    }

    Map<String, Object> data = new HashMap<>(rawData);

    String operationId =
        data.containsKey("operationId") && data.get("operationId") != null
            ? data.get("operationId").toString()
            : "";

    String internalRef =
        data.get("internalRefNumber") != null
            ? data.get("internalRefNumber").toString()
            : (data.get("channelRefNumber") != null ? data.get("channelRefNumber").toString() : "");

    String channelRef =
        data.get("channelRefNumber") != null
            ? data.get("channelRefNumber").toString()
            : (data.get("internalRefNumber") != null
                ? data.get("internalRefNumber").toString()
                : "");

    String sinpeRef =
        data.get("sinpeRefNumber") != null ? data.get("sinpeRefNumber").toString() : "";

    Object rawDebited =
        data.get("debitedAmount") != null ? data.get("debitedAmount") : data.get("amount");
    BigDecimal debitedAmount =
        rawDebited != null ? new BigDecimal(rawDebited.toString()) : fallbackAmount;

    String debitCurrencyCode =
        data.get("debitCurrencyCode") != null
            ? data.get("debitCurrencyCode").toString()
            : (data.get("currency") != null ? data.get("currency").toString() : fallbackCurrency);

    BigDecimal commissionAmount =
        data.get("commissionAmount") != null
            ? new BigDecimal(data.get("commissionAmount").toString())
            : BigDecimal.ZERO;

    String commissionCurrency =
        data.get("commissionCurrency") != null
            ? data.get("commissionCurrency").toString()
            : (debitCurrencyCode != null ? debitCurrencyCode : "");

    BigDecimal exchangeRate =
        data.get("exchangeRate") != null
            ? new BigDecimal(data.get("exchangeRate").toString())
            : BigDecimal.ZERO;

    String registrationDate =
        data.get("registrationDate") != null ? data.get("registrationDate").toString() : "";
    String processingDate =
        data.get("processingDate") != null ? data.get("processingDate").toString() : "";

    // 4. Normalización de Estado (stateCode & stateDescription en Inglés)
    Integer stateCode = 32;
    Object rawState = data.containsKey("stateCode") ? data.get("stateCode") : data.get("state");
    if (rawState != null) {
      try {
        stateCode = Double.valueOf(rawState.toString()).intValue();
      } catch (Exception ignored) {
      }
    }

    String stateDescription;
    switch (stateCode) {
      case 1:
        stateDescription = "Registered";
        break;
      case 32:
        stateDescription = "Completed";
        break;
      case 128:
        stateDescription = "Rejected";
        break;
      case 256:
        stateDescription = "Pending";
        break;
      default:
        stateDescription = "Completed";
        break;
    }

    // 5. Manejo de Rechazo (rejectCode & rejectDescription)
    Integer rejectCode = 0;
    if (data.get("rejectCode") != null) {
      try {
        rejectCode = Double.valueOf(data.get("rejectCode").toString()).intValue();
      } catch (Exception ignored) {
      }
    } else if (stateCode == 128) {
      rejectCode = 128;
    }

    String rejectDescription =
        data.get("rejectDescription") != null ? data.get("rejectDescription").toString() : "";
    if (rejectDescription.isEmpty() && stateCode == 128) {
      rejectDescription = "Transaction rejected";
    }

    boolean successful =
        data.get("successful") != null
            ? Boolean.parseBoolean(data.get("successful").toString())
            : (stateCode == 32);

    Object customData = data.get("customData");

    // Limpieza de campos viejos/no homologados que venían en el raw map
    data.remove("amount");
    data.remove("currency");
    data.remove("state");

    // 6. Inyección de las llaves homologadas estandarizadas
    data.put("operationId", operationId);
    data.put("internalRefNumber", internalRef);
    data.put("channelRefNumber", channelRef);
    data.put("sinpeRefNumber", sinpeRef);
    data.put("debitedAmount", debitedAmount);
    data.put("debitCurrencyCode", debitCurrencyCode);
    data.put("commissionAmount", commissionAmount);
    data.put("commissionCurrency", commissionCurrency);
    data.put("exchangeRate", exchangeRate);
    data.put("registrationDate", registrationDate);
    data.put("processingDate", processingDate);
    data.put("stateCode", stateCode);
    data.put("stateDescription", stateDescription);
    data.put("rejectCode", rejectCode);
    data.put("rejectDescription", rejectDescription);
    data.put("successful", successful);
    data.put("customData", customData);

    return data;
  }
  
  
}
