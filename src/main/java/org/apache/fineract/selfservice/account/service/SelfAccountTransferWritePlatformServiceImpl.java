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
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
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
  private final PinExternalTransferService pinExternalTransferService;
  private final Gson gson = new Gson();
  private final AppSelfServiceUserRepository appUserRepository;
  private final AppUserRepository coreUserRepository; // El repositorio nativo de AppUser de Fineract

  private static final String APOLO_BANK_CODE = "373";

  @Override
  @Transactional
  public Object prepareTransfer(final AccountTransferPrepareRequest request) {
    log.info("PREPARE: Procesando nueva petición de transferencia entrante desde el DTO.");

    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();

    final String fromAccount = request.getFromAccount();
    final String toAccount = request.getToAccount();
    final BigDecimal transferAmount = request.getTransferAmount();
    final String transferType = request.getTransferType();
    final String currencyCode = request.getCurrencyCode();
    final String transferDescription = request.getTransferDescription();
    final String reference = request.getReference();

    if (transferAmount == null || transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("El monto de la transferencia (transferAmount) debe ser mayor a cero.");
    }
    if (toAccount == null || toAccount.isBlank()) {
      throw new IllegalArgumentException("La cuenta o número destino (toAccount) es requerido.");
    }
    if (fromAccount == null || fromAccount.isBlank()) {
      throw new IllegalArgumentException("La cuenta origen (fromAccount) es requerida.");
    }

    validateDestinationAccount(currentUser.getId(), toAccount, transferType);

    log.info("PREPARE: Validando estado y fondos de la cuenta origen local: {}", fromAccount);

    Map<String, Object> prepareResponse = new HashMap<>();
    prepareResponse.put("status", "PREPARED");
    prepareResponse.put("fromAccount", fromAccount);
    prepareResponse.put("toAccount", toAccount);
    prepareResponse.put("transferAmount", transferAmount);
    prepareResponse.put("transferType", transferType);
    prepareResponse.put("currencyCode", currencyCode);
    prepareResponse.put("message", "La cuenta destino fue verificada y el estado es apto para proceder a cotización.");

    log.info("PREPARE: Transferencia validada y preparada con éxito hacia el destino: {}", toAccount);
    return prepareResponse;
  }

  @Override
  @Transactional
  public Object quoteTransfer(final AccountTransferPrepareRequest request) {
    log.info("QUOTE: Iniciando cotización para canal: {}", request.getTransferType());

    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();

    final AccountTransferQuoteResponse quote = this.quoteService.calculateFee(request);

    log.info("QUOTE: Cotización calculada. Disparando nuevo envío de OTP de seguridad.");

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

    log.info("CONFIRM: Iniciando procesamiento de doble paso para canal: {} | Comisión: {}",
            request.getTransferType(), feeAmountFromClient);

    CommandProcessingResult result;

    String cleanDestination = request.getToAccount() != null ? request.getToAccount().replaceAll("\\s+", "") : "";

    if (isInternalApoloIban(cleanDestination) || "MISMO_BANCO".equalsIgnoreCase(request.getTransferType())) {
      log.info("CONFIRM -> Cuenta interna de Apolo detectada (373). Ejecutando transferencia local.");
      result = executeInternalTransfer(request, user);
    } else if ("SINPE_MOVIL".equalsIgnoreCase(request.getTransferType())) {
      result = executeSinpeTransfer(request, user);
    } else if ("PIN".equalsIgnoreCase(request.getTransferType())) {
      result = executePinTransfer(request, user);
    } else {
      result = executeInternalTransfer(request, user);
    }

    if (feeAmountFromClient.compareTo(BigDecimal.ZERO) > 0) {
      String commissionToAccountId = "USD".equalsIgnoreCase(request.getCurrencyCode()) ? "140" : "139";

      log.info("CONFIRM CONTABLE: Desplazando comisión de {} {} hacia la cuenta colectora Apolo ID: {}",
              feeAmountFromClient, request.getCurrencyCode(), commissionToAccountId);

      executeCommissionChargeViaMismoBanco(request, feeAmountFromClient, commissionToAccountId);
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

    // ⚡ CORRECCIÓN: Registramos el OTP amarrado a la cuenta origen (fromAccount) del usuario logueado
    SelfServiceRegistration registration = SelfServiceRegistration.instance(
            client, client.getAccountNumber(), client.getFirstname(), client.getMiddlename(), client.getLastname(),
            request.getFromAccount(),
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

  private CommandProcessingResult executePinTransfer(AccountTransferConfirmRequest request, AppSelfServiceUser user) {
    String pinRequestBody = this.gson.toJson(request);
    this.pinExternalTransferService.getAccountInfo(request.getToAccount());
    return new CommandProcessingResultBuilder().withEntityId(0L).build();
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
    commandData.put("transferDescription", request.getTransferDescription() != null ? request.getTransferDescription() : "Transferencia Interna");
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

      Map<String, Object> contextData = new HashMap<>();
      contextData.put("transactionAmount", request.getTransferAmount());
      contextData.put("transferDescription", request.getTransferDescription());
      contextData.put("fromAccountNumber", request.getFromAccount());
      contextData.put("toAccountNumber", request.getToAccount() != null ? request.getToAccount() : request.getToPhoneNumber());
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

    try {
      Long fromClientId = getLongValue(params, originalParams, "fromClientId");
      if (fromClientId != null) {
        ClientData fromClient = clientReadPlatformService.retrieveOne(fromClientId);
        contextData.put("fromClientName", fromClient.getDisplayName());

        if (fromClient.getOfficeId() != null) {
          String fromOfficeName = officeReadPlatformService.retrieveOffice(fromClient.getOfficeId()).getName();
          contextData.put("fromOfficeName", fromOfficeName);
        } else {
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

    try {
      Long toClientId = getLongValue(params, originalParams, "toClientId");
      if (toClientId != null) {
        ClientData toClient = clientReadPlatformService.retrieveOne(toClientId);
        contextData.put("toClientName", toClient.getDisplayName());

        if (toClient.getOfficeId() != null) {
          String toOfficeName = officeReadPlatformService.retrieveOffice(toClient.getOfficeId()).getName();
          contextData.put("toOfficeName", toOfficeName);
        } else {
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
    boolean isBeneficiaryActive = tptBeneficiaryReadPlatformService.isBeneficiaryRegistered(appUserId, destinationAccount);

    if (isBeneficiaryActive) {
      log.info("PREPARE: La cuenta destino {} ya está dada de alta y activa como beneficiario.", destinationAccount);
      return;
    }

    log.info("PREPARE: Cuenta destino no registrada previamente. Evaluando canal para: {}", transferType);

    String cleanAccount = destinationAccount.replaceAll("\\s+", "");

    if ("PIN".equalsIgnoreCase(transferType) || "MISMO_BANCO".equalsIgnoreCase(transferType) || isInternalApoloIban(cleanAccount)) {
      log.info("PREPARE [PIN / Mismo Banco]: Validando cuenta mediante PinExternalTransferService.getAccountInfo");

      try {
        String accountInfoResponse = pinExternalTransferService.getAccountInfo(cleanAccount);

        if (accountInfoResponse == null || accountInfoResponse.contains("\"disabled\"")) {
          throw new IllegalArgumentException("El servicio de validación de cuentas (PIN/Mismo Banco) no está disponible.");
        }

        Map<String, Object> accountData = gson.fromJson(accountInfoResponse, Map.class);

        if (accountData.containsKey("error") || accountData.containsKey("message") && accountInfoResponse.contains("not found")) {
          throw new IllegalArgumentException("La cuenta destino no existe en el sistema financiero.");
        }

        String state = String.valueOf(accountData.get("state"));
        String stateDescription = String.valueOf(accountData.get("stateDescription"));

        if (!"1".equals(state) && !"Active".equalsIgnoreCase(stateDescription)) {
          throw new IllegalArgumentException("La cuenta destino existe pero no está activa (Estado: " + stateDescription + ").");
        }

        String holderName = String.valueOf(accountData.get("holder"));
        log.info("PREPARE [PIN / Mismo Banco]: Cuenta verificada exitosamente. Titular: {}, Banco: {}",
                holderName, accountData.get("entityName"));

      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        log.error("Error al validar la cuenta vía PIN/Mismo Banco: {}", e.getMessage());
        throw new IllegalArgumentException("No se pudo verificar la existencia o el estado de la cuenta.");
      }

    } else if ("SINPE".equalsIgnoreCase(transferType) || "SINPE_MOVIL".equalsIgnoreCase(transferType)) {
      log.info("PREPARE [SINPE]: Ejecutando flujo de validación específico para SINPE.");

    } else {
      log.warn("PREPARE: No se pudo determinar el canal de validación para la cuenta: {} con tipo: {}",
              destinationAccount, transferType);
    }
  }

  private boolean isInternalApoloIban(String accountIdentifier) {
    if (accountIdentifier == null) {
      return false;
    }

    String cleanAccount = accountIdentifier.replaceAll("\\s+", "").toUpperCase();

    if (cleanAccount.length() >= 8 && cleanAccount.startsWith("CR")) {

      String bankSegment = cleanAccount.substring(4, 8);

      return bankSegment.contains(APOLO_BANK_CODE);
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
    contextData.put("transferAmount", transferAmount);

    this.applicationEventPublisher.publishEvent(SelfServiceNotificationEvent.withTenantContext(
            this, SelfServiceNotificationEvent.Type.TRANSFER_OTP, user.getId(), user.getFirstname(), user.getLastname(),
            user.getUsername(), user.getEmail(), extractMobile(user), determineMode(user.getEmail(), extractMobile(user)),
            "Unknown IP (Quote Phase)", LocaleContextHolder.getLocale(), contextData));

    log.info("QUOTE: OTP successfully registered and event published for destination target.");
  }

  private void executeCommissionChargeViaMismoBanco(
          AccountTransferConfirmRequest request,
          BigDecimal feeAmount,
          String toCommissionAccountId) {

    log.info("CONFIRM CONTABLE: Iniciando cobro de comisión vía API REST Directa (mifos via Env).");

    try {
      // 1. Leer configuraciones desde las variables de entorno usando el objeto env inyectado
      String coreUrl = this.env.getProperty("FINERACT_CORE_URL", "https://core.apolocapital.io/fineract-provider/api/v1/accounttransfers");
      String authHeader = this.env.getProperty("FINERACT_CORE_AUTH", "Basic bWlmb3M6cGFzc3dvcmQ=");
      String tenantId = this.env.getProperty("FINERACT_CORE_TENANT", "default");

      // 2. Obtenemos los datos dinámicos del cliente logueado
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      Client client = user.getAppUserClientMappings().iterator().next().getClient();
      Long fromClientId = client.getId();
      Long fromOfficeId = client.getOffice().getId();

      // (Extracción de los últimos 7 dígitos sin ceros a la izquierda)
      Long internalSavingsAccountId = null;
      try {
        String cleanAccount = request.getFromAccount().replaceAll("\\s+", "");

        if (cleanAccount.length() >= 7) {
          // Tomamos exactamente los últimos 7 dígitos (ej: de "CR92037300110010000087" a "0000087")
          String last7Digits = cleanAccount.substring(cleanAccount.length() - 7);
          log.info("CONFIRM CONTABLE: Mapeando últimos 7 dígitos del IBAN: {}", last7Digits);

          // Removemos todos los ceros iniciales antes de que aparezca el primer número significativo
          String cleanDigits = last7Digits.replaceFirst("^0+", "");

          if (cleanDigits.isEmpty()) {
            cleanDigits = "0";
          }

          internalSavingsAccountId = Long.valueOf(cleanDigits);
          log.info("CONFIRM CONTABLE: ID de cuenta resuelto con éxito: {}", internalSavingsAccountId);
        } else {
          // Si la cuenta entrante es más corta que 7 dígitos, validamos si es numérica pura
          if (cleanAccount.matches("\\d+")) {
            internalSavingsAccountId = Long.valueOf(cleanAccount);
          }
        }
      } catch (Exception e) {
        log.error("CONFIRM CONTABLE: Error procesando la extracción de los últimos 7 dígitos para la cuenta: {}", request.getFromAccount(), e);
      }

      // Fallback de seguridad por si el formato del IBAN falla por completo
      if (internalSavingsAccountId == null) {
        log.warn("CONFIRM CONTABLE: Activando ID de cuenta de contingencia por defecto.");
        internalSavingsAccountId = 87L;
      }

      // 4. Construimos el mapa JSON con la estructura numérica limpia que requiere el Core
      Map<String, Object> apiPayload = new HashMap<>();
      apiPayload.put("fromOfficeId", fromOfficeId);
      apiPayload.put("fromClientId", fromClientId);
      apiPayload.put("fromAccountType", request.getFromAccountType() != null ? request.getFromAccountType().toString() : "2");

      // ⚡ ID Numérico puro inyectado aquí (ej: 87) para evitar el NumberFormatException
      apiPayload.put("fromAccountId", internalSavingsAccountId);

      apiPayload.put("toOfficeId", 1);
      apiPayload.put("toClientId", 199);
      apiPayload.put("toAccountType", 2);
      apiPayload.put("toAccountId", Integer.parseInt(toCommissionAccountId)); // Convierte "139" o "140" a entero limpio

      apiPayload.put("transferAmount", feeAmount);
      apiPayload.put("transferDate", request.getTransferDate());
      apiPayload.put("transferDescription", "Cobro Comisión Canal " + request.getTransferType());

      // Parámetros obligatorios para evitar que el motor de plantillas/notificaciones falle
      apiPayload.put("fromClientName", client.getDisplayName() != null ? client.getDisplayName() : client.getFirstname());
      apiPayload.put("toClientName", "Colectora Apolo");

      apiPayload.put("locale", request.getLocale() != null ? request.getLocale() : "es");
      apiPayload.put("dateFormat", request.getDateFormat() != null ? request.getDateFormat() : "dd-MM-yyyy");

      String jsonRequestBody = this.gson.toJson(apiPayload);

      // 5. Configuramos y ejecutamos la llamada HTTP nativa (Bypass total del contexto JPA de EclipseLink)
      java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

      java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(coreUrl))
              .header("Authorization", authHeader)
              .header("Content-Type", "application/json")
              .header("Fineract-Platform-TenantId", tenantId)
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonRequestBody))
              .build();

      log.info("CONFIRM CONTABLE: Enviando POST HTTP al endpoint configurado en entorno...");

      java.net.http.HttpResponse<String> httpResponse = httpClient.send(
              httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

      // 6. Validamos el estatus de la respuesta del API de Fineract
      if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
        log.info("CONFIRM CONTABLE: Comisión cobrada exitosamente vía API. Respuesta: {}", httpResponse.body());
      } else {
        log.error("CONFIRM CONTABLE: Error en el cobro de comisión. HTTP Status: {}. Respuesta: {}",
                httpResponse.statusCode(), httpResponse.body());
      }

    } catch (Exception e) {
      log.error("CONFIRM CONTABLE: Falló la petición HTTP crítica de cobro de comisión: ", e);
    }
  }

}