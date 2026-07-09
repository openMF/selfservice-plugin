package org.apache.fineract.selfservice.account.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.security.SecureRandom;
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
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.apache.fineract.selfservice.account.service.AccountTransferQuoteService;
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
    description = "Endpoints for 3-step account transfers (Prepare, Quote, Confirm)")
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  private final AccountTransferQuoteService quoteService;
  private final SinpeExternalApiClient sinpeExternalApiClient;
  private final SelfServiceRegistrationRepository registrationRepository;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

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
      result = executeInternalTransfer(request);
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
    
    SelfServiceRegistration registration = registrationRepository
        .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
            client.getId(), SelfServiceRequestType.ACCOUNT_TRANSFER, request.getOtp())
        .orElse(null); // Usamos orElse(null) para validar manualmente

    // Validamos si el OTP es nulo, ya fue consumido o ha expirado
    if (registration == null 
        || registration.isConsumed() 
        || registration.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      
      // Construcción del error de validación siguiendo el estándar de Fineract
      final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
      final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("otp");
      baseDataValidator.reset()
          .parameter("otp")
          .value(request.getOtp())
          .failWithCode("invalid.or.expired", "Invalid or expired OTP.");
      
      // Lanzamos la excepción nativa de Fineract que mapea a HTTP 400
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    // Si el OTP es válido, lo marcamos como consumido
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
            .originIban(
                request.getFromAccountId()) // Assuming fromAccountId holds the IBAN for SINPE
            .destinationPhone(request.getToPhoneNumber())
            .amount(request.getTransferAmount())
            .currencyCode("CRC")
            .description(request.getTransferDescription())
            .debitIBAN(true)
            .customData(List.of(new SinpeTransferRequest.CustomData("Source", "SelfServiceApp")))
            .build();

    sinpeExternalApiClient.transferToPhone(sinpeRequest);

    return new CommandProcessingResultBuilder()
        .withEntityId(0L) // External transfers don't have an internal Fineract ID
        .build();
  }

  private CommandProcessingResult executeInternalTransfer(AccountTransferConfirmRequest request) {
    String json = new Gson().toJson(request);
    final CommandWrapper commandRequest =
        new CommandWrapperBuilder().createAccountTransfer().withJson(json).build();

    return commandsSourceWritePlatformService.logCommandSource(commandRequest);
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
