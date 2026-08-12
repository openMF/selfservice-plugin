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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import  org.apache.fineract.portfolio.account.data.AccountTransferData;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.TransactionDateManagementService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.paymentdetail.data.PaymentDetailData;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmResponse;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.FeeCollectionRequest;
import org.apache.fineract.selfservice.account.data.FeeCollectionResult;
import org.apache.fineract.selfservice.account.data.PaymentDetailUpdateRequest;
import org.apache.fineract.selfservice.account.data.ResendOtpRequest;
import org.apache.fineract.selfservice.account.data.SameBankTransferCustomData;
import org.apache.fineract.selfservice.account.data.SameBankTransferResponseData;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountTransferRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAuditRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAuditRepository;
import org.apache.fineract.selfservice.account.exception.BeneficiaryTransferLimitExceededException;
import org.apache.fineract.selfservice.account.exception.DailyTPTTransactionAmountLimitExceededException;
import org.apache.fineract.selfservice.api.data.TransactionDateRequest;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
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
  private final JdbcTemplate jdbcTemplate;
  private final Gson gson = new Gson();

  private final SavingsAccountAssembler savingsAccountAssembler;
  private final LoanAssembler loanAssembler;
  private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;

  private final SelfServiceSameBankTransferAuditRepository sameBankTransferAuditRepository;

  private static final DateTimeFormatter REF_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  private static final String FINERACT_TRANSFER_DATE_FORMAT = "dd MMMM yyyy";
  private static final String FINERACT_TRANSFER_LOCALE = "en";

  private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
  private final SelfServiceAccountTransferRepository selfServiceAccountTransferRepository;
  private final SelfServiceTransferAuditRepository transferAuditRepository;
  private final SelfServiceFeeCollectionService feeCollectionService;

  /** Centralized multi-tenant LocalDate / LocalDateTime / format helpers. */
  private final TransactionDateUtil transactionDateUtil;

  /**
   * Higher-level API date processing (validates + formats via {@link TransactionDateUtil}). Used so
   * any client-supplied transfer date is normalized tenant-safely before policy overrides it.
   */
  private final TransactionDateManagementService transactionDateManagementService;

  private final NotificationDeliveryModeUtil notificationDeliveryModeUtil;
  
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  
  private final PaymentDetailService paymentDetailService;
  
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
    final String transferDescription = buildTransferDescription(request);
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

    SavingsAccount sourceSavingsAccount =
        validateSourceAccountOwnership(currentUser, fromAccount, request.getFromAccountType());

    validateDestinationAccount(currentUser.getId(), toAccount, transferType);

    log.info("PREPARE: Validating status and funds of the local source account: {}", fromAccount);

    BigDecimal feeAmount = BigDecimal.ZERO;
    try {
      AccountTransferQuoteResponse quote =
          this.quoteService.calculateFee(request, sourceSavingsAccount.getClient());
      if (quote != null && quote.getFeeAmount() != null) {
        feeAmount = quote.getFeeAmount();
      }
    } catch (Exception e) {
      log.warn("PREPARE: Could not calculate fee during prepare stage, defaulting to 0", e);
    }

    BigDecimal totalAmount = transferAmount.add(feeAmount);

    validateSufficientFunds(sourceSavingsAccount, transferAmount, feeAmount);

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
        "The destination account was verified and the status is suitable to proceed with the"
            + " quote.");

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
    SavingsAccount sourceSavingsAccount =
        validateSourceAccountOwnership(
            currentUser, request.getFromAccount(), request.getFromAccountType());

    Client sourceClient = sourceSavingsAccount.getClient();

    final AccountTransferQuoteResponse quote =
        this.quoteService.calculateFee(request, sourceClient);

    validateSufficientFunds(
        sourceSavingsAccount, request.getTransferAmount(), quote.getFeeAmount());

    log.info("QUOTE: Quote calculated. Triggering new security OTP dispatch.");

    String destinationTarget =
        "SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())
            ? request.getToPhoneNumber()
            : request.getToAccount();

    cleanupOldOtpRegistrations(sourceClient);
    releaseOtpCooldown(currentUser);

    generateAndSendOtpForQuote(
        currentUser, sourceClient, destinationTarget, request.getTransferAmount());
    return this.gson.toJson(quote);
  }

  private void cleanupOldOtpRegistrations(Client client) {
    try {
      LocalDateTime cutoff = transactionDateUtil.getCurrentTenantLocalDateTime().minusMinutes(10);
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

  private void releaseTransferSuccessCooldown(AppSelfServiceUser user) {
    try {
      String cacheKey =
          SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS.name() + ":" + user.getId();
      notificationCooldownCache.release(cacheKey);
      log.info(
          "CONFIRM: Released TRANSFER_SUCCESS cooldown key={} for user {}", cacheKey, user.getId());
    } catch (Exception e) {
      log.warn("Failed to release TRANSFER_SUCCESS cooldown (non-fatal)", e);
    }
  }

  // =====================================================================
  //  CONFIRM
  // =====================================================================
  @Override
  @Transactional
  public Object confirmTransfer(
      AccountTransferConfirmRequest request, HttpServletRequest httpRequest) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    user.validateHasCreatePermission("ACCOUNTTRANSFER");

    SavingsAccount sourceSavingsAccount =
        validateSourceAccountOwnership(
            user, request.getFromAccount(), request.getFromAccountType());

    Client sourceClient = sourceSavingsAccount.getClient();
    validateOtp(request, sourceClient);

    BigDecimal feeAmountFromClient =
        request.getFeeAmount() != null ? request.getFeeAmount() : BigDecimal.ZERO;

    BigDecimal feeForBalanceCheck = feeAmountFromClient;
    if (feeForBalanceCheck.compareTo(BigDecimal.ZERO) <= 0) {
      try {
        AccountTransferPrepareRequest quoteReq = new AccountTransferPrepareRequest();
        quoteReq.setTransferType(request.getTransferType());
        quoteReq.setCurrencyCode(request.getCurrencyCode());
        quoteReq.setTransferMode(request.getTransferMode());
        quoteReq.setTransferAmount(request.getTransferAmount());
        AccountTransferQuoteResponse quote = quoteService.calculateFee(quoteReq, sourceClient);
        if (quote != null && quote.getFeeAmount() != null) {
          feeForBalanceCheck = quote.getFeeAmount();
        }
      } catch (Exception e) {
        log.warn("CONFIRM: Could not re-calculate fee for balance check, using client value", e);
      }
    }

    validateSufficientFunds(sourceSavingsAccount, request.getTransferAmount(), feeForBalanceCheck);

    log.info(
        "CONFIRM: Starting two-step processing for channel: {} | Fee: {}",
        request.getTransferType(),
        feeAmountFromClient);

    String cleanDestination =
        request.getToAccount() != null ? request.getToAccount().replaceAll("\\s+", "") : "";

    if ("PIN".equalsIgnoreCase(request.getTransferType())) {
      log.info("CONFIRM -> PIN account detected. Executing PIN transfer.");
      return executePinTransfer(request, user, sourceSavingsAccount, httpRequest);
    } else if ("SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())) {
      log.info("CONFIRM -> SINPE_MOVIL account detected. Executing SINPE_MOVIL transfer.");
      return executeSinpeTransfer(request, user, sourceSavingsAccount, httpRequest);
    } else if (isSameBankIbanAccount(cleanDestination)
        || "SAME_BANK".equalsIgnoreCase(request.getTransferType())) {
      log.info("CONFIRM -> Internal account detected. Executing local transfer.");
      return executeInternalTransfer(request, user, sourceSavingsAccount, httpRequest);
    } else {
      log.info("CONFIRM -> Fallback to internal transfer.");
      return executeInternalTransfer(request, user, sourceSavingsAccount, httpRequest);
    }
  }

  // =====================================================================
  //  CREATE TRANSFER  (legacy TPT path)
  // =====================================================================
  @Override
  @Transactional
  public CommandProcessingResult createTransfer(
      String type, String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
    Map<String, Object> params = dataValidator.validateCreate(type, apiRequestBodyAsJson);
    if ("tpt".equals(type)) {
      checkForLimits(params);
    }

    JsonCommand command = createJsonCommand(apiRequestBodyAsJson);
    CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

    publishTransferEvent(result, params, params, httpRequest);
    return result;
  }

  /**
   * Builds the transfer date string for Apache Fineract account-transfer commands.
   *
   * <p><b>Date policy (centralized, multi-tenant):</b> any {@code transferDate} submitted by the
   * REST client is discarded. The value is always the current tenant date formatted as {@code dd
   * MMMM yyyy} (locale {@code en}) so Fineract never rejects it as a future date.
   */
  private String getTransferDateForApacheFineract(AccountTransferConfirmRequest request) {
    if (request != null && StringUtils.isNotBlank(request.getTransferDate())) {
      log.info(
          "CONFIRM: discarding client-submitted transferDate='{}' (format='{}') in favour of tenant date",
          request.getTransferDate(),
          request.getDateFormat());
      try {
        TransactionDateRequest dateRequest =
            new TransactionDateRequest(
                request.getTransferDate(),
                StringUtils.defaultIfBlank(request.getDateFormat(), "dd-MM-yyyy"),
                FINERACT_TRANSFER_LOCALE);
        OffsetDateTime processed =
            transactionDateManagementService.processAndValidateTransactionDate(dateRequest);
        log.debug("Client transferDate processed via TransactionDateManagementService → {}", processed);
      } catch (Exception e) {
        log.debug("Client transferDate could not be processed (non-fatal): {}", e.getMessage());
      }
    }

    final String tenantToday =
        transactionDateUtil.getCurrentDateForFineract(
            FINERACT_TRANSFER_DATE_FORMAT, FINERACT_TRANSFER_LOCALE);

    log.info(
        "CONFIRM: transferDate forced to tenant date='{}' (format='{}', locale='{}')",
        tenantToday,
        FINERACT_TRANSFER_DATE_FORMAT,
        FINERACT_TRANSFER_LOCALE);
    return tenantToday;
  }

  /** Tenant-aware "now" as OffsetDateTime for audit / response timestamps. */
  private OffsetDateTime currentTenantOffsetDateTime() {
    LocalDateTime tenantLdt = transactionDateUtil.getCurrentTenantLocalDateTime();
    return tenantLdt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  // =====================================================================
  //  SAME_BANK / internal
  // =====================================================================
  private Object executeInternalTransfer(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      SavingsAccount sourceSavingsAccount,
      HttpServletRequest httpRequest) {

    Client client = sourceSavingsAccount.getClient();
    Long fromClientId = client.getId();
    Long fromOfficeId = client.getOffice().getId();

    Long fromAccountId = sourceSavingsAccount.getId();
    Long toAccountId = resolveAccountId(request.getToAccount(), request.getToAccountType());

    SavingsAccount fromSavingsAccount = sourceSavingsAccount;
    SavingsAccount toSavingsAccount =
        savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(toAccountId);

    Long toClientId = toSavingsAccount.getClient().getId();
    Long toOfficeId = toSavingsAccount.getClient().getOffice().getId();

    if (toClientId == null) {
      throw new IllegalArgumentException(
          "Could not determine destination client for internal transfer.");
    }

    String resolvedCurrencyCode =
        resolveCurrencyCode(fromSavingsAccount, request.getCurrencyCode());

    String transferDateForFineract = this.getTransferDateForApacheFineract(request);
    String localeForFineract = FINERACT_TRANSFER_LOCALE;
    String dateFormatForFineract = FINERACT_TRANSFER_DATE_FORMAT;

    Map<String, Object> commandData = new HashMap<>();
    commandData.put("paymentTypeId", 1);
    commandData.put("fromOfficeId", fromOfficeId);
    commandData.put("fromClientId", fromClientId);
    commandData.put("fromAccountType", 2);
    commandData.put("fromAccountId", fromAccountId);
    commandData.put("toOfficeId", toOfficeId);
    commandData.put("toClientId", toClientId);
    commandData.put("toAccountType", 2);
    commandData.put("toAccountId", toAccountId);
    commandData.put("transferAmount", request.getTransferAmount());
    commandData.put("transferDate", transferDateForFineract);
    commandData.put("transferDescription", buildTransferDescription(request));
    commandData.put("locale", localeForFineract);
    commandData.put("dateFormat", dateFormatForFineract);

    String jsonRequestBody = gson.toJson(commandData);

    if (StringUtils.isBlank(jsonRequestBody)) {
      log.error("Failed to serialize command data to JSON. commandData: {}", commandData);
      throw new IllegalArgumentException(
          "Internal error: Failed to serialize transfer command data.");
    }

    log.info("JSON Request Body for Internal Transfer: {}", jsonRequestBody);

    JsonCommand command = createJsonCommand(jsonRequestBody);

    OffsetDateTime registrationDate = currentTenantOffsetDateTime();

    CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

    log.info("JSON Response Body for Internal Transfer: {}", result.toString());

    OffsetDateTime processingDate = currentTenantOffsetDateTime();

    SavingsAccountTransaction transferTransaction = null;
    OffsetDateTime instant = currentTenantOffsetDateTime();
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
      if (instant != null) {
        processingDate = instant;
      }
      log.info("Fetching created_on_tz: {} ", processingDate);
      if (transferTransaction != null) {
        String refNo = transferTransaction.getRefNo();
        log.info("Fetching RefNo: {} ", operationId);
        if (refNo != null) {
          operationId = refNo;
        }
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
    
    SavingsTxnPair savingxTxnPair = this.resolveSavingsTransactionIds(result.getResourceId());

    SavingsAccountTransactionData transactionData = null;
    if (savingxTxnPair.fromId != null) {
        transactionData = this.savingsAccountReadPlatformService.retrieveSavingsTransaction(
            fromAccountId,
            savingxTxnPair.fromId,
            DepositAccountType.SAVINGS_DEPOSIT
        );
    }
    
    PaymentDetailData paymentDetailData = transactionData.getPaymentDetailData();


    Long paymentDetailId = paymentDetailData.getId();
    if (paymentDetailId != null) {
        PaymentDetailUpdateRequest updateReq = new PaymentDetailUpdateRequest(paymentDetailId, internalRefNumber);
        paymentDetailService.updateRoutingCode(updateReq);
    }

    String fromAccountIdentifier =
        StringUtils.isNotBlank(request.getFromAccount())
            ? request.getFromAccount().replaceAll("\\s+", "")
            : resolveAccountIdentifier(fromSavingsAccount);
    String toAccountIdentifier =
        StringUtils.isNotBlank(request.getToAccount())
            ? request.getToAccount().replaceAll("\\s+", "")
            : resolveAccountIdentifier(toSavingsAccount);

    Map<String, Object> destinationCustomer =
        buildDestinationCustomer(toSavingsAccount, toAccountIdentifier);

    // PIN-parity customData: identifiers + destinationCustomer + legacy fee fields
    SameBankTransferCustomData customData =
        SameBankTransferCustomData.builder()
            .totalAmount(totalAmount.toPlainString())
            .transferDescription(buildTransferDescription(request))
            .feeAmount(feeAmount.toPlainString())
            .debitAmount(transferAmount.toPlainString())
            .exchangeRateAmount("1")
            .fromAccountIdentifier(fromAccountIdentifier)
            .toAccountIdentifier(toAccountIdentifier)
            .destinationCustomer(destinationCustomer)
            .reference(StringUtils.defaultString(request.getReference()))
            .build();

    SameBankTransferResponseData responseData =
        SameBankTransferResponseData.builder()
            .commissionAmount(feeAmount)
            .commissionCurrency(resolvedCurrencyCode)
            .customData(customData)
            .debitCurrencyCode(resolvedCurrencyCode)
            .debitedAmount(transferAmount)
            .exchangeRate(BigDecimal.ONE)
            .operationId(operationId)
            .processingDate(processingDate.toString())
            .registrationDate(registrationDate.toString())
            .rejectDescription("")
            .channelRefNumber(internalRefNumber)
            .internalRefNumber(internalRefNumber)
            .stateDescription(description)
            .stateCode(stateCode)
            .successful(stateCode == 32)
            .build();

    Long fromSavingsTxnId = null;
    Long toSavingsTxnId = null;
    SavingsTxnPair satPair = resolveSavingsTransactionIds(result.getResourceId());
    if (satPair != null) {
      fromSavingsTxnId = satPair.fromId();
      toSavingsTxnId = satPair.toId();
    } else if (result.getResourceId() != null) {
      // Fallback: some Fineract builds return savings transaction id as resourceId
      fromSavingsTxnId = result.getResourceId();
    }

    log.info("Persist the audit trail");
    persistSameBankTransferAudit(
        client.getId(),
        fromAccountId,
        toAccountId,
        fromAccountIdentifier,
        toAccountIdentifier,
        transferAmount,
        feeAmount,
        resolvedCurrencyCode,
        operationId,
        internalRefNumber,
        result.getResourceId(),
        fromSavingsTxnId,
        toSavingsTxnId,
        buildTransferDescription(request),
        request.getReference(),
        description,
        stateCode == 32,
        "",
        registrationDate,
        processingDate);

    executeFeeTransaction(request, sourceSavingsAccount);

    log.info("Homologating SAME_BANK response structure");
    Map<String, Object> rawInternalMap = gson.fromJson(gson.toJson(responseData), Map.class);
    Map<String, Object> homologatedData =
        homologateResponseData(rawInternalMap, request.getTransferAmount(), resolvedCurrencyCode);

    // Ensure destinationCustomer survives homologation even if DTO JSON omits nested maps
    ensureSameBankCustomData(
        homologatedData, fromAccountIdentifier, toAccountIdentifier, destinationCustomer, request);

    AccountTransferConfirmResponse wrappedResponse =
        AccountTransferConfirmResponse.builder()
            .transferType("SAME_BANK")
            .data(homologatedData)
            .build();
    releaseTransferSuccessCooldown(user);
    publishFastPaymentTransferEvent(result, request, user, client, httpRequest);

    log.info(
        "CONFIRM SAME_BANK: Transfer completed. operationId={}, internalRefNumber={},"
            + " fineractTransferId={}, fromSat={}, toSat={}",
        operationId,
        internalRefNumber,
        result.getResourceId(),
        fromSavingsTxnId,
        toSavingsTxnId);

    return wrappedResponse;
  }

  private record SavingsTxnPair(Long fromId, Long toId) {}

  /**
   * Resolves from/to savings transaction ids via m_account_transfer_transaction when resourceId is
   * the account-transfer id. Multi-tenant safe (tenant DataSource routing).
   */
  private SavingsTxnPair resolveSavingsTransactionIds(Long resourceId) {
    if (resourceId == null) {
      return null;
    }
    try {
      final String sql =
          "SELECT from_savings_transaction_id, to_savings_transaction_id "
              + "FROM m_account_transfer_transaction WHERE id = ?";
      Map<String, Object> row = jdbcTemplate.queryForMap(sql, resourceId);
      Long fromId =
          row.get("from_savings_transaction_id") != null
              ? ((Number) row.get("from_savings_transaction_id")).longValue()
              : null;
      Long toId =
          row.get("to_savings_transaction_id") != null
              ? ((Number) row.get("to_savings_transaction_id")).longValue()
              : null;
      if (fromId != null || toId != null) {
        return new SavingsTxnPair(fromId, toId);
      }
    } catch (Exception e) {
      log.debug(
          "Could not resolve savings txn ids from account_transfer_transaction id={}: {}",
          resourceId,
          e.getMessage());
    }
    return null;
  }

  private String resolveAccountIdentifier(SavingsAccount account) {
    if (account == null) {
      return "";
    }
    try {
      if (account.getExternalId() != null
          && StringUtils.isNotBlank(account.getExternalId().getValue())) {
        return account.getExternalId().getValue();
      }
    } catch (Exception ignored) {
      // fall through
    }
    return StringUtils.defaultString(account.getAccountNumber());
  }

  private Map<String, Object> buildDestinationCustomer(
      SavingsAccount toSavingsAccount, String toAccountIdentifier) {
    Map<String, Object> destinationCustomer = new HashMap<>();
    Client toClient = toSavingsAccount != null ? toSavingsAccount.getClient() : null;

    String name = "";
    String email = "";
    if (toClient != null) {
      name = StringUtils.defaultString(toClient.getDisplayName());
      if (StringUtils.isBlank(name)) {
        name = StringUtils.defaultString(toClient.getFullname());
      }
      email = StringUtils.defaultString(toClient.getEmailAddress());
    }

    String documentKey = "";
    String idType = "0";
    String idTypeDescription = "Persona Física Nacional (Cédula)";
    if (toClient != null && toClient.getId() != null) {
      try {
        final String sql =
            "SELECT COALESCE(ci.document_key, '') AS document_key, "
                + "COALESCE(CAST(cv.order_position AS VARCHAR), '0') AS id_type, "
                + "COALESCE(cv.code_value, 'Persona Física Nacional (Cédula)') AS id_type_description "
                + "FROM m_client_identifier ci "
                + "LEFT JOIN m_code_value cv ON ci.document_type_id = cv.id "
                + "WHERE ci.client_id = ? "
                + "ORDER BY ci.id ASC LIMIT 1";
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, toClient.getId());
        documentKey = String.valueOf(row.getOrDefault("document_key", ""));
        idType = String.valueOf(row.getOrDefault("id_type", "0"));
        idTypeDescription =
            String.valueOf(
                row.getOrDefault("id_type_description", "Persona Física Nacional (Cédula)"));
      } catch (Exception e) {
        log.debug(
            "Could not load destination client identifier for clientId={}: {}",
            toClient.getId(),
            e.getMessage());
      }
    }

    destinationCustomer.put("name", name);
    destinationCustomer.put("id", documentKey);
    destinationCustomer.put("idType", StringUtils.isNotBlank(idType) ? idType : "0");
    destinationCustomer.put("idTypeDescription", idTypeDescription);
    destinationCustomer.put("email", email);
    destinationCustomer.put(
        "iban", StringUtils.isNotBlank(toAccountIdentifier) ? toAccountIdentifier : "");
    return destinationCustomer;
  }

  @SuppressWarnings("unchecked")
  private void ensureSameBankCustomData(
      Map<String, Object> homologatedData,
      String fromAccountIdentifier,
      String toAccountIdentifier,
      Map<String, Object> destinationCustomer,
      AccountTransferConfirmRequest request) {

    Map<String, Object> customData;
    Object existing = homologatedData.get("customData");
    if (existing instanceof Map) {
      customData = new HashMap<>((Map<String, Object>) existing);
    } else {
      customData = new HashMap<>();
    }

    customData.putIfAbsent("fromAccountIdentifier", fromAccountIdentifier);
    customData.putIfAbsent("toAccountIdentifier", toAccountIdentifier);
    customData.putIfAbsent("destinationCustomer", destinationCustomer);
    if (!customData.containsKey("transferDescription")
        || customData.get("transferDescription") == null
        || customData.get("transferDescription").toString().isBlank()) {
      customData.put("transferDescription", buildTransferDescription(request));
    }
    if (!customData.containsKey("reference")) {
      customData.put("reference", StringUtils.defaultString(request.getReference()));
    }

    homologatedData.put("customData", customData);
    homologatedData.putIfAbsent("exchangeRate", BigDecimal.ONE);
    homologatedData.putIfAbsent("rejectCode", 0);
    homologatedData.putIfAbsent("sinpeRefNumber", "");
  }

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

  private String generateInternalRefNumber(
      OffsetDateTime dateTime, Long officeId, Long resourceId) {
    String datePart = dateTime.format(REF_DATE_FMT);
    String officePart = String.format("%05d", officeId != null ? officeId : 0L);
    String resourcePart = String.format("%012d", resourceId != null ? resourceId : 0L);
    return datePart + officePart + resourcePart;
  }

  private void persistTransferAudit(
      Long clientId,
      String transferType,
      String currencyCode,
      BigDecimal transferAmount,
      BigDecimal feeAmount,
      String status) {
    try {
      SelfServiceTransferAudit audit =
          SelfServiceTransferAudit.builder()
              .clientId(clientId)
              .transferType(transferType)
              .currencyCode(currencyCode)
              .transferAmount(transferAmount)
              .feeAmount(feeAmount)
              .processingDate(currentTenantOffsetDateTime())
              .status(status)
              .build();
      transferAuditRepository.saveAndFlush(audit);
    } catch (Exception e) {
      log.error("Failed to persist transfer audit", e);
    }
  }

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
      Long fromSavingsTransactionId,
      Long toSavingsTransactionId,
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
              processingDate,
              fromSavingsTransactionId, 
              toSavingsTransactionId);

      // Requires entity fields + Liquibase 069 (from_savings_transaction_id / to_savings_transaction_id)
      audit.setFromSavingsTransactionId(fromSavingsTransactionId);
      audit.setToSavingsTransactionId(toSavingsTransactionId);

      sameBankTransferAuditRepository.saveAndFlush(audit);
      log.info(
          "SAME_BANK audit persisted: operationId={}, internalRefNumber={}, fromSat={}, toSat={}",
          operationId,
          internalRefNumber,
          fromSavingsTransactionId,
          toSavingsTransactionId);
    } catch (Exception e) {
      log.error(
          "Failed to persist SAME_BANK transfer audit (non-fatal): operationId={}", operationId, e);
    }
  }

  // =====================================================================
  //  PIN / SINPE — unchanged logic; fee path uses centralized transfer date
  // =====================================================================
  private Object executePinTransfer(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      SavingsAccount sourceSavingsAccount,
      HttpServletRequest httpRequest) {
    log.info(
        "CONFIRM PIN: Starting PIN flow with strict destination and origin metadata validation.");

    try {
      Client client = sourceSavingsAccount.getClient();

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
            "The destination account did not return valid holder or currency information. Transfer"
                + " canceled.");
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
            "CONFIRM PIN: Aborting transfer. Could not determine the origin client name in"
                + " Fineract.");
        throw new IllegalArgumentException(
            "Origin client identity could not be verified. Transfer canceled to avoid external"
                + " rejections.");
      }

      org.apache.fineract.selfservice.account.data.PinTransferRequest pinRequest =
          new org.apache.fineract.selfservice.account.data.PinTransferRequest();
      pinRequest.setAmount(request.getTransferAmount());
      pinRequest.setCurrency(dynamicCurrencyCode);
      pinRequest.setDescription(buildTransferDescription(request));
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

      executeFeeTransaction(request, sourceSavingsAccount);

      Map<String, Object> externalData = gson.fromJson(pinServiceResponse, Map.class);

      Map<String, Object> homologatedData =
          homologateResponseData(externalData, request.getTransferAmount(), dynamicCurrencyCode);

      Map<String, Object> response = new HashMap<>();
      response.put("transferType", "PIN");
      response.put("data", homologatedData);
      releaseTransferSuccessCooldown(user);
      publishPinTransferEvent(request, user, client, httpRequest, externalData);

      return response;

    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      log.error("CONFIRM PIN: Unexpected critical error executing PIN transfer: ", e);
      throw new RuntimeException("Error processing external PIN transfer.", e);
    }
  }

  private Object executeSinpeTransfer(
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      SavingsAccount sourceSavingsAccount,
      HttpServletRequest httpRequest) {
    Client client = sourceSavingsAccount.getClient();

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
            .description(buildTransferDescription(request))
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

    executeFeeTransaction(request, sourceSavingsAccount);

    Map<String, Object> externalData = gson.fromJson(sinpeServiceResponse, Map.class);

    Map<String, Object> homologatedData =
        homologateResponseData(externalData, request.getTransferAmount(), "CRC");

    Map<String, Object> response = new HashMap<>();
    response.put("transferType", "SINPE_MOVIL");
    response.put("data", homologatedData);
    releaseTransferSuccessCooldown(user);
    publishSinpeTransferEvent(request, user, client, httpRequest, externalData);

    return response;
  }

  // =====================================================================
  //  NOTIFICATION PUBLISHERS
  // =====================================================================
  private void publishFastPaymentTransferEvent(
      CommandProcessingResult result,
      AccountTransferConfirmRequest request,
      AppSelfServiceUser user,
      Client sourceClient,
      HttpServletRequest httpRequest) {
    try {
      releaseTransferSuccessCooldown(user);

      String mobileNumber = extractMobile(user, sourceClient);
      boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put("transferDescription", buildTransferDescription(request));
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
          transactionDateUtil.getCurrentDateForFineract(
              FINERACT_TRANSFER_DATE_FORMAT, FINERACT_TRANSFER_LOCALE));
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      contextData.put("fromClientName", displayNameOrUnknown(sourceClient));

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
      Client sourceClient,
      HttpServletRequest httpRequest,
      Map<String, Object> externalData) {
    try {
      releaseTransferSuccessCooldown(user);

      String mobileNumber = extractMobile(user, sourceClient);
      boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put("transferDescription", buildTransferDescription(request));
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
          transactionDateUtil.getCurrentDateForFineract(
              FINERACT_TRANSFER_DATE_FORMAT, FINERACT_TRANSFER_LOCALE));
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      contextData.put("fromClientName", displayNameOrUnknown(sourceClient));

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
      Client sourceClient,
      HttpServletRequest httpRequest,
      Map<String, Object> externalData) {
    try {
      releaseTransferSuccessCooldown(user);

      String mobileNumber = extractMobile(user, sourceClient);
      boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);
      String ipAddress = extractClientIp(httpRequest);

      Map<String, Object> contextData = new HashMap<>();
      contextData.put(
          "transactionAmount",
          request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
      contextData.put("transferDescription", buildTransferDescription(request));
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
          transactionDateUtil.getCurrentDateForFineract(
              FINERACT_TRANSFER_DATE_FORMAT, FINERACT_TRANSFER_LOCALE));
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      contextData.put("fromClientName", displayNameOrUnknown(sourceClient));

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

  private String displayNameOrUnknown(Client client) {
    if (client == null || StringUtils.isBlank(client.getDisplayName())) {
      return "N/A";
    }
    return client.getDisplayName();
  }

  private void publishTransferEvent(
      CommandProcessingResult result,
      Map<String, Object> params,
      Map<String, Object> originalParams,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);
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
          transactionDateUtil.getCurrentDateForFineract(
              FINERACT_TRANSFER_DATE_FORMAT, FINERACT_TRANSFER_LOCALE));
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
      Client sourceClient,
      HttpServletRequest httpRequest) {
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = transactionDateUtil.getCurrentTenantLocalDateTime().plusMinutes(10);

    SelfServiceRegistration registration =
        SelfServiceRegistration.instance(
            sourceClient,
            sourceClient.getAccountNumber(),
            sourceClient.getFirstname(),
            sourceClient.getMiddlename(),
            sourceClient.getLastname(),
            request.getFromAccount() != null ? request.getFromAccount() : "N/A",
            user.getEmail(),
            otp,
            otp,
            user.getUsername(),
            "TRANSFER_OTP",
            SelfServiceRequestType.ACCOUNT_TRANSFER,
            expiry);

    registrationRepository.saveAndFlush(registration);
    registration.markDispatched();
    registrationRepository.saveAndFlush(registration);

    log.info(
        "OTP GENERATED: registrationId={}, clientId={}, expiresAt={}",
        registration.getId(),
        sourceClient.getId(),
        expiry);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put(
        "transferAmount",
        request.getTransferAmount() != null ? request.getTransferAmount().toString() : "N/A");
    contextData.put("resend", true);

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.TRANSFER_OTP,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            extractMobile(user, sourceClient),
            notificationDeliveryModeUtil.determineMode(
                user.getEmail(), extractMobile(user, sourceClient)),
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
    response.put("expiresAt", expiry.toString());
    response.put("otpId", registration.getId());
    return gson.toJson(response);
  }

  private void validateOtp(AccountTransferConfirmRequest request, Client sourceClient) {
    SelfServiceRegistration registration =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                sourceClient.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER, request.getOtp())
            .orElse(null);

    if (registration == null
        || registration.isConsumed()
        || registration.isExpired(transactionDateUtil.getCurrentTenantLocalDateTime())) {
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

  private String extractMobile(AppSelfServiceUser user, Client sourceClient) {
    if (sourceClient != null && StringUtils.isNotBlank(sourceClient.getMobileNo())) {
      return sourceClient.getMobileNo();
    }
    return extractMobile(user);
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
          "PREPARE [SAME_BANK]: Destination is an internal account. Validation will be performed"
              + " via external ID resolution during execution.");
    } else if ("PIN".equalsIgnoreCase(transferType) || isSameBankIbanAccount(cleanAccount)) {
      log.info(
          "PREPARE [PIN / Same Bank IBAN]: Validating account via"
              + " PinExternalTransferService.getAccountInfo");
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
      AppSelfServiceUser user,
      Client sourceClient,
      String destinationTarget,
      BigDecimal transferAmount) {
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = transactionDateUtil.getCurrentTenantLocalDateTime().plusMinutes(10);

    SelfServiceRegistration registration =
        SelfServiceRegistration.instance(
            sourceClient,
            sourceClient.getAccountNumber(),
            sourceClient.getFirstname(),
            sourceClient.getMiddlename(),
            sourceClient.getLastname(),
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
            extractMobile(user, sourceClient),
            notificationDeliveryModeUtil.determineMode(
                user.getEmail(), extractMobile(user, sourceClient)),
            "Unknown IP (Quote Phase)",
            LocaleContextHolder.getLocale(),
            contextData));

    log.info("QUOTE: OTP successfully registered and event published for destination target.");
  }

  private void executeFeeTransaction(
      AccountTransferConfirmRequest request, SavingsAccount sourceSavingsAccount) {
    log.info(
        "ACCOUNTING CONFIRM: Delegating fee collection for type={}, currency={}, amount={}",
        request.getTransferType(),
        request.getCurrencyCode(),
        request.getTransferAmount());

    Client client = sourceSavingsAccount.getClient();

    try {
      FeeCollectionRequest feeReq =
          FeeCollectionRequest.builder()
              .transferType(request.getTransferType())
              .currencyCode(request.getCurrencyCode())
              .transferMode(request.getTransferMode())
              .transferAmount(request.getTransferAmount())
              .fromAccount(request.getFromAccount())
              .fromAccountType(
                  request.getFromAccountType() != null ? request.getFromAccountType() : 2)
              .clientId(client.getId())
              .fromOfficeId(client.getOffice().getId())
              .transferDateForFineract(getTransferDateForApacheFineract(request))
              .dateFormat(FINERACT_TRANSFER_DATE_FORMAT)
              .locale(FINERACT_TRANSFER_LOCALE)
              .clientFeeAmount(request.getFeeAmount())
              .build();

      FeeCollectionResult result = feeCollectionService.collectFee(feeReq);

      log.info(
          "ACCOUNTING CONFIRM: Fee collection result → status={}, txnId={}, fee={} {}",
          result.getStatus(),
          result.getTransactionId(),
          result.getFeeAmount(),
          result.getCurrency());

    } catch (Exception e) {
      log.error(
          "ACCOUNTING CONFIRM: Commission collection failed (non-fatal). "
              + "Original transfer remains intact.",
          e);
      persistTransferAudit(
          client.getId(),
          request.getTransferType(),
          request.getCurrencyCode(),
          request.getTransferAmount(),
          request.getFeeAmount() != null ? request.getFeeAmount() : BigDecimal.ZERO,
          "FAILED");
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
          "BENEFICIARY VALIDATION: Error executing query on m_selfservice_beneficiaries_tpt for"
              + " account: {}",
          destinationAccount,
          e);
      return false;
    }
  }

  private Map<String, String> getSinpeProperties() {
    String sql =
        "SELECT esp.name, esp.value FROM c_external_service_properties esp JOIN c_external_service"
            + " es ON esp.external_service_id = es.id WHERE es.name = 'SinpeService'";
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
    SavingsAccount sourceSavingsAccount =
        validateSourceAccountOwnership(user, resendRequest.getFromAccount(), null);
    Client sourceClient = sourceSavingsAccount.getClient();

    log.info("RESEND OTP: Starting for userId={}, clientId={}", user.getId(), sourceClient.getId());

    List<SelfServiceRegistration> activeOtps =
        registrationRepository.findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            sourceClient.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER);

    if (!activeOtps.isEmpty()) {
      log.info(
          "RESEND OTP: Found {} active OTP(s) – marking them expired/consumed", activeOtps.size());
      for (SelfServiceRegistration reg : activeOtps) {
        markAsExpired(reg);
      }
    } else {
      log.info("RESEND OTP: No active OTP found → generating fresh one.");
      return generateNewOtpForResend(user, sourceClient, resendRequest, httpRequest);
    }

    releaseOtpCooldown(user);

    Object result = generateNewOtpForResend(user, sourceClient, resendRequest, httpRequest);

    log.info("RESEND OTP: Completed for userId={}", user.getId());
    return result;
  }

  private Object generateNewOtpForResend(
      AppSelfServiceUser user,
      Client sourceClient,
      ResendOtpRequest resendRequest,
      HttpServletRequest httpRequest) {
    AccountTransferConfirmRequest dummy = new AccountTransferConfirmRequest();
    dummy.setFromAccount(resendRequest.getFromAccount());
    dummy.setToAccount(resendRequest.getToAccount());
    dummy.setTransferType(resendRequest.getTransferType());
    dummy.setTransferDescription(resendRequest.getTransferDescription());
    dummy.setTransferAmount(BigDecimal.ZERO);
    return generateAndSendOtp(dummy, user, sourceClient, httpRequest);
  }

  private void markAsExpired(SelfServiceRegistration reg) {
    reg.setExpiresAt(transactionDateUtil.getCurrentTenantLocalDateTime().minusSeconds(1));
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
            : BigDecimal.ONE;

    String registrationDate =
        data.get("registrationDate") != null ? data.get("registrationDate").toString() : "";
    String processingDate =
        data.get("processingDate") != null ? data.get("processingDate").toString() : "";

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

    data.remove("amount");
    data.remove("currency");
    data.remove("state");

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

  private void validateSufficientFunds(
      String fromAccountIdentifier,
      Integer fromAccountType,
      BigDecimal transferAmount,
      BigDecimal feeAmount) {

    if (StringUtils.isBlank(fromAccountIdentifier)) {
      throw new IllegalArgumentException(
          "Source account (fromAccount) is required for balance validation.");
    }

    BigDecimal transfer = transferAmount != null ? transferAmount : BigDecimal.ZERO;
    BigDecimal fee = feeAmount != null ? feeAmount : BigDecimal.ZERO;
    BigDecimal totalRequired = transfer.add(fee);

    if (totalRequired.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    Long fromAccountId =
        resolveAccountId(fromAccountIdentifier, fromAccountType != null ? fromAccountType : 2);

    SavingsAccount fromSavings =
        savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(fromAccountId);

    validateSufficientFunds(fromSavings, transferAmount, feeAmount);
  }

  private void validateSufficientFunds(
      SavingsAccount fromSavings, BigDecimal transferAmount, BigDecimal feeAmount) {

    BigDecimal transfer = transferAmount != null ? transferAmount : BigDecimal.ZERO;
    BigDecimal fee = feeAmount != null ? feeAmount : BigDecimal.ZERO;
    BigDecimal totalRequired = transfer.add(fee);

    if (totalRequired.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    BigDecimal available;
    try {
      available = fromSavings.getWithdrawableBalance();
    } catch (Exception e) {
      log.warn(
          "getWithdrawableBalance() unavailable, falling back to account balance. cause={}",
          e.getMessage());
      available =
          fromSavings.getSummary() != null
              ? fromSavings.getSummary().getAccountBalance()
              : fromSavings.getAccountBalance();
    }

    if (available == null) {
      available = BigDecimal.ZERO;
    }

    if (available.compareTo(totalRequired) < 0) {
      final List<ApiParameterError> errors = new ArrayList<>();
      final DataValidatorBuilder base =
          new DataValidatorBuilder(errors).resource("accounttransfer");
      base.reset()
          .parameter("transferAmount")
          .value(totalRequired)
          .failWithCode(
              "insufficient.account.balance",
              "Insufficient funds in source account. Available: "
                  + available.toPlainString()
                  + ", required (transfer + fee): "
                  + totalRequired.toPlainString()
                  + ".");
      throw new PlatformApiDataValidationException(errors);
    }

    log.info(
        "FUNDS CHECK OK: accountId={}, available={}, transfer={}, fee={}, totalRequired={}",
        fromSavings.getId(),
        available,
        transfer,
        fee,
        totalRequired);
  }

  private SavingsAccount validateSourceAccountOwnership(
      AppSelfServiceUser user, String fromAccountIdentifier, Integer fromAccountType) {
    SavingsAccount sourceSavingsAccount =
        resolveSourceSavingsAccountForOwnership(fromAccountIdentifier, fromAccountType);
    Long sourceClientId =
        sourceSavingsAccount.getClient() != null ? sourceSavingsAccount.getClient().getId() : null;

    if (sourceClientId == null || !mappedClientIds(user).contains(sourceClientId)) {
      throw invalidSourceAccountException();
    }

    return sourceSavingsAccount;
  }

  private SavingsAccount resolveSourceSavingsAccountForOwnership(
      String fromAccountIdentifier, Integer fromAccountType) {
    try {
      PortfolioAccountType type =
          PortfolioAccountType.fromInt(fromAccountType != null ? fromAccountType : 2);
      if (type != PortfolioAccountType.SAVINGS) {
        throw invalidSourceAccountException();
      }

      Long fromAccountId = resolveAccountId(fromAccountIdentifier, type.getValue());
      return savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(fromAccountId);
    } catch (PlatformApiDataValidationException
        | AbstractPlatformResourceNotFoundException
        | IllegalArgumentException e) {
      log.debug("Source account ownership validation failed.", e);
      throw invalidSourceAccountException();
    }
  }

  private Set<Long> mappedClientIds(AppSelfServiceUser user) {
    if (user == null || user.getAppUserClientMappings() == null) {
      return Set.of();
    }

    return user.getAppUserClientMappings().stream()
        .map(AppSelfServiceUserClientMapping::getClient)
        .filter(Objects::nonNull)
        .map(Client::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private PlatformApiDataValidationException invalidSourceAccountException() {
    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder base = new DataValidatorBuilder(errors).resource("accounttransfer");
    base.reset()
        .parameter("fromAccount")
        .failWithCode("invalid.source.account", "Source account is invalid or unavailable.");
    return new PlatformApiDataValidationException(errors);
  }

  private String buildTransferDescription(AccountTransferPrepareRequest request) {
    String description = request.getTransferDescription();
    String mode = request.getTransferMode();

    boolean useOriginal =
        description != null
            && !description.isBlank()
            && description.length() >= 15
            && mode != null
            && (mode.equalsIgnoreCase("PIN")
                || mode.equalsIgnoreCase("SINPE")
                || mode.equalsIgnoreCase("SAME_BANK"));

    String result =
        useOriginal
            ? description
            : (description != null && !description.isBlank() ? description : "Transfer")
                + " via "
                + (mode != null ? mode : "");

    return String.format("%-15s", result);
  }

  private String buildTransferDescription(AccountTransferConfirmRequest request) {
    if (request == null) {
      return String.format("%-15s", "Transfer");
    }
    String description = request.getTransferDescription();
    String mode = request.getTransferMode();
    if (StringUtils.isBlank(mode)) {
      mode = request.getTransferType();
    }

    boolean useOriginal =
        description != null
            && !description.isBlank()
            && description.length() >= 15
            && mode != null
            && (mode.equalsIgnoreCase("PIN")
                || mode.equalsIgnoreCase("SINPE")
                || mode.equalsIgnoreCase("SAME_BANK"));

    String result =
        useOriginal
            ? description
            : (description != null && !description.isBlank() ? description : "Transfer")
                + " via "
                + (mode != null ? mode : "");

    return String.format("%-15s", result);
  }
}