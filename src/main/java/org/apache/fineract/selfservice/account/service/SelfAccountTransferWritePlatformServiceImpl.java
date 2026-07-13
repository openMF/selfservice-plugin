/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
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
  private final Gson gson = new Gson();

  @Override
  @Transactional
  public Object prepareTransfer(AccountTransferPrepareRequest request) {
    if (request.getTransferAmount() == null || request.getTransferAmount().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Transfer amount must be greater than zero.");
    }
    return gson.toJson(request);
  }

  @Override
  @Transactional
  public Object quoteTransfer(AccountTransferPrepareRequest request) {
    AccountTransferQuoteResponse quote = quoteService.calculateFee(request);
    return gson.toJson(quote);
  }

  @Override
  @Transactional
  public Object confirmTransfer(AccountTransferConfirmRequest request, HttpServletRequest httpRequest) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    if (StringUtils.isBlank(request.getOtp())) {
      return generateAndSendOtp(request, user, httpRequest);
    }

    validateOtp(request, user);

    CommandProcessingResult result;
    if ("SINPE_MOVIL".equals(request.getTransferType())) {
      result = executeSinpeTransfer(request, user);
    } else {
      result = executeInternalTransfer(request, user);
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

    publishTransferEvent(result, params,params, httpRequest);
    return result;
  }

  private String generateAndSendOtp(AccountTransferConfirmRequest request, AppSelfServiceUser user, HttpServletRequest httpRequest) {
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);

    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    SelfServiceRegistration registration = SelfServiceRegistration.instance(
        client, client.getAccountNumber(), client.getFirstname(), client.getMiddlename(), client.getLastname(),
        request.getToPhoneNumber() != null ? request.getToPhoneNumber() : request.getToAccountId(),
        user.getEmail(), otp, otp, user.getUsername(), "TRANSFER_OTP", SelfServiceRequestType.ACCOUNT_TRANSFER, expiry);
    registrationRepository.saveAndFlush(registration);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    contextData.put("transferAmount", request.getTransferAmount());

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

  private CommandProcessingResult executeInternalTransfer(AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    // Construir el JSON con los datos necesarios
    Map<String, Object> commandData = new HashMap<>();
    commandData.put("fromAccountId", request.getFromAccountId());
    commandData.put("toAccountId", request.getToAccountId());
    commandData.put("fromAccountType", request.getFromAccountType());
    commandData.put("toAccountType", request.getToAccountType());
    commandData.put("transferAmount", request.getTransferAmount());
    commandData.put("transferDate", request.getTransferDate());
    commandData.put("transferDescription", request.getTransferDescription());
    commandData.put("locale", "en");
    commandData.put("dateFormat", "yyyy-MM-dd");    
    // Agregar otros campos necesarios (por ejemplo, si aplica)
    String apiRequestBodyAsJson = gson.toJson(commandData);

    // Usar el mismo mecanismo que createTransfer
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

      Map<String, Object> contextData = new HashMap<>();
      contextData.put("transactionAmount", request.getTransferAmount());
      contextData.put("transferDescription", request.getTransferDescription());
      contextData.put("fromAccountNumber", request.getFromAccountId());
      contextData.put("toAccountNumber", request.getToAccountId() != null ? request.getToAccountId() : request.getToPhoneNumber());
      contextData.put("transferId", result.getResourceId() != null ? result.getResourceId() : "EXT-" + System.currentTimeMillis());

      applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
          this, SelfServiceNotificationEvent.Type.TRANSFER_SUCCESS, user.getId(), user.getFirstname(), user.getLastname(),
          user.getUsername(), user.getEmail(), mobileNumber, emailMode, extractClientIp(httpRequest),
          LocaleContextHolder.getLocale(), contextData));
    } catch (Exception e) {
      log.warn("Failed to publish transfer notification event", e);
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
      
      // Populate all mandatory fields using both params and originalParams as fallback
      contextData.put("transactionAmount", getFieldValue(params, originalParams, 
          "transactionAmount", "transferAmount", "amount"));
      contextData.put("transferDescription", getFieldValue(params, originalParams, 
          "transferDescription", "description"));
      contextData.put("transactionDate", getFieldValue(params, originalParams, 
          "transactionDate", "transferDate"));
      contextData.put("fromAccountNumber", getFieldValue(params, originalParams, 
          "fromAccountNumber", "fromAccountId"));
      contextData.put("toAccountNumber", getFieldValue(params, originalParams, 
          "toAccountNumber", "toAccountId"));
      contextData.put("transferId", result.getResourceId() != null ? result.getResourceId() : "N/A");
      contextData.put("ipAddress", StringUtils.isNotBlank(ipAddress) ? ipAddress : "Unknown");

      // Resolve client and office names - use originalParams as primary source since it has fromClientId/toClientId
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
    
    // Resolve FROM client and office
    try {
      Long fromClientId = getLongValue(params, originalParams, "fromClientId");
      if (fromClientId != null) {
        ClientData fromClient = clientReadPlatformService.retrieveOne(fromClientId);
        contextData.put("fromClientName", fromClient.getDisplayName());
        
        if (fromClient.getOfficeId() != null) {
          String fromOfficeName = officeReadPlatformService.retrieveOffice(fromClient.getOfficeId()).getName();
          contextData.put("fromOfficeName", fromOfficeName);
        } else {
          // Try to get office name from fromOfficeId if available
          Long fromOfficeId = getLongValue(params, originalParams, "fromOfficeId");
          if (fromOfficeId != null) {
            contextData.put("fromOfficeName", officeReadPlatformService.retrieveOffice(fromOfficeId).getName());
          } else {
            contextData.put("fromOfficeName", "N/A");
          }
        }
      } else {
        contextData.put("fromClientName", "N/A");
        contextData.put("fromOfficeName", "N/A");
      }
    } catch (Exception e) {
      log.warn("Failed to resolve FROM client/office names: {}", e.getMessage());
      contextData.putIfAbsent("fromClientName", "N/A");
      contextData.putIfAbsent("fromOfficeName", "N/A");
    }

    // Resolve TO client and office
    try {
      Long toClientId = getLongValue(params, originalParams, "toClientId");
      if (toClientId != null) {
        ClientData toClient = clientReadPlatformService.retrieveOne(toClientId);
        contextData.put("toClientName", toClient.getDisplayName());
        
        if (toClient.getOfficeId() != null) {
          String toOfficeName = officeReadPlatformService.retrieveOffice(toClient.getOfficeId()).getName();
          contextData.put("toOfficeName", toOfficeName);
        } else {
          // Try to get office name from toOfficeId if available
          Long toOfficeId = getLongValue(params, originalParams, "toOfficeId");
          if (toOfficeId != null) {
            contextData.put("toOfficeName", officeReadPlatformService.retrieveOffice(toOfficeId).getName());
          } else {
            contextData.put("toOfficeName", "N/A");
          }
        }
      } else {
        contextData.put("toClientName", "N/A");
        contextData.put("toOfficeName", "N/A");
      }
    } catch (Exception e) {
      log.warn("Failed to resolve TO client/office names: {}", e.getMessage());
      contextData.putIfAbsent("toClientName", "N/A");
      contextData.putIfAbsent("toOfficeName", "N/A");
    }
  }

  /**
   * Gets a field value from either params or originalParams, trying multiple possible key names.
   * Returns the first non-null, non-empty value found, or "N/A" if none found.
   */
  private Object getFieldValue(Map<String, Object> params, Map<String, Object> originalParams, String... possibleKeys) {
    for (String key : possibleKeys) {
      // First try params
      Object value = params.get(key);
      if (isNotEmpty(value)) {
        return value;
      }
      // Then try originalParams
      value = originalParams.get(key);
      if (isNotEmpty(value)) {
        return value;
      }
    }
    return "N/A";
  }
  
  /**
   * Gets a Long value from either params or originalParams.
   */
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

  /**
   * Checks if a value is not null and not empty string.
   */
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
}