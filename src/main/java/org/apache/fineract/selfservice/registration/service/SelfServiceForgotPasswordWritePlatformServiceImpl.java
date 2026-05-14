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
package org.apache.fineract.selfservice.registration.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.campaigns.sms.data.SmsProviderData;
import org.apache.fineract.infrastructure.campaigns.sms.domain.SmsCampaign;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.EmailDetail;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.infrastructure.sms.domain.SmsMessage;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageStatusType;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.registration.exception.SelfServiceRegistrationNotFoundException;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.SelfServiceUserDomainService;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

@Slf4j
@RequiredArgsConstructor
public class SelfServiceForgotPasswordWritePlatformServiceImpl
    implements SelfServiceForgotPassworWritePlatformService {

  private final SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  private final FromJsonHelper fromApiJsonHelper;
  private final SelfServiceRegistrationReadPlatformService
      selfServiceRegistrationReadPlatformService;
  private final ClientRepositoryWrapper clientRepository;
  private final PasswordValidationPolicyRepository passwordValidationPolicy;
  private final SelfServiceUserDomainService userDomainService;
  private final SelfServicePluginEmailService selfServicePluginEmailService;
  private final SmsMessageRepository smsMessageRepository;
  private final SmsMessageScheduledJobService smsMessageScheduledJobService;
  private final SmsCampaignDropdownReadPlatformService smsCampaignDropdownReadPlatformService;
  private final AppSelfServiceUserReadPlatformService appUserReadPlatformService;
  private final RoleRepository roleRepository;
  private final AppSelfServiceUserClientMappingRepository appUserClientMappingRepository;
  private final JdbcTemplate jdbcTemplate;
  private final AppUserRepository appUserRepository;
  private final Environment env;
  private final PlatformPasswordEncoder platformPasswordEncoder;
  private final AppSelfServiceUserRepository appSelfServiceUserRepository;
  private final SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  private final ITemplateEngine registrationTemplateEngine;
  private final MessageSource registrationMessageSource;

  @Override
  public SelfServiceRegistration createForgotPasswordRequest(String apiRequestBodyAsJson) {
    Gson gson = new Gson();
    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("user");
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap,
        apiRequestBodyAsJson,
        SelfServiceApiConstants.FORGOT_PASSWORD_REQUEST_DATA_PARAMETERS);
    JsonElement element = gson.fromJson(apiRequestBodyAsJson, JsonElement.class);

    String username =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.usernameParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.usernameParamName)
        .value(username)
        .notBlank()
        .notExceedingLengthOf(100);

    String externalId = extractExternalId(element);
    if (externalId != null) {
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.externalIdParamName)
          .value(externalId)
          .notBlank()
          .notExceedingLengthOf(100);
    }

    String authenticationMode =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.authenticationModeParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.authenticationModeParamName)
        .value(authenticationMode)
        .notBlank()
        .isOneOfTheseStringValues(
            SelfServiceApiConstants.emailModeParamName,
            SelfServiceApiConstants.mobileModeParamName);

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    AppSelfServiceUserClientMapping mapping = resolveUserMapping(username);
    if (mapping == null) {
      return null;
    }
    Client client = mapping.getClient();
    AppSelfServiceUser appUser = mapping.getAppUser();
    if (!matchesExternalId(externalId, client)) {
      return null;
    }

    boolean isEmailAuthenticationMode =
        SelfServiceApiConstants.emailModeParamName.equalsIgnoreCase(authenticationMode);
    String email = appUser.getEmail();
    String mobileNumber = client.getMobileNo();
    if (isEmailAuthenticationMode && StringUtils.isBlank(email)) {
      return null;
    }
    if (!isEmailAuthenticationMode && StringUtils.isBlank(mobileNumber)) {
      return null;
    }

    LocalDateTime createdAt = DateUtils.getLocalDateTimeOfSystem();
    String token = selfServiceAuthorizationTokenService.generateToken();
    SelfServiceRegistration request =
        SelfServiceRegistration.instance(
            client,
            client.getAccountNumber(),
            client.getFirstname(),
            client.getMiddlename(),
            client.getLastname(),
            mobileNumber,
            email,
            token,
            token,
            username,
            SelfServiceRegistration.PASSWORD_RESET_SENTINEL,
            SelfServiceRequestType.PASSWORD_RESET,
            selfServiceAuthorizationTokenService.calculateExpiry(createdAt));
    selfServiceRegistrationRepository.saveAndFlush(request);
    trySendAuthorizationToken(request, isEmailAuthenticationMode);
    return request;
  }

  @Transactional(rollbackFor = Exception.class)
  @Override
  public CommandProcessingResult renewPassword(String apiRequestBodyAsJson) {
    Gson gson = new Gson();
    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("user");
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap,
        apiRequestBodyAsJson,
        SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS);
    JsonElement element = gson.fromJson(apiRequestBodyAsJson, JsonElement.class);

    String password =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.passwordParamName, element);
    String repeatPassword =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.repeatPasswordParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .notBlank()
        .notExceedingLengthOf(100);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.repeatPasswordParamName)
        .value(repeatPassword)
        .notBlank()
        .notExceedingLengthOf(100);

    final PasswordValidationPolicy validationPolicy =
        this.passwordValidationPolicy.findActivePasswordValidationPolicy();
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .matchesRegularExpression(validationPolicy.getRegex(), validationPolicy.getDescription())
        .notExceedingLengthOf(100);

    if (password != null && !password.equals(repeatPassword)) {
      dataValidationErrors.add(
          ApiParameterError.parameterError(
              "error.msg.password.confirmation.mismatch",
              "Password and repeatPassword must match.",
              SelfServiceApiConstants.repeatPasswordParamName,
              repeatPassword));
    }

    SelfServiceRegistration request =
        resolvePasswordResetRequest(element, baseDataValidator, dataValidationErrors);
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    AppSelfServiceUser appUser =
        this.appSelfServiceUserRepository.findAppSelfServiceUserByName(request.getUsername());
    if (appUser == null) {
      throw new PlatformDataIntegrityException(
          "error.msg.user.notfound.username",
          "User with username " + request.getUsername() + " not found.",
          SelfServiceApiConstants.usernameParamName,
          request.getUsername());
    }

    appUser.updatePassword(encodePassword(password));
    appUser.updatePasswordResetRequired(false);
    appSelfServiceUserRepository.saveAndFlush(appUser);
    request.markConsumed();
    try {
      selfServiceRegistrationRepository.saveAndFlush(request);
    } catch (OptimisticLockingFailureException e) {
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.request.token.invalid",
          "The supplied self-service token is expired or already used.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName);
    }

    return new CommandProcessingResultBuilder().withEntityId(appUser.getId()).build();
  }

  private SelfServiceRegistration resolvePasswordResetRequest(
      JsonElement element,
      DataValidatorBuilder baseDataValidator,
      List<ApiParameterError> dataValidationErrors) {
    String externalToken =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.externalAuthenticationTokenParamName, element);
    if (externalToken != null) {
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.externalAuthenticationTokenParamName)
          .value(externalToken)
          .notBlank()
          .notExceedingLengthOf(100);
      if (!dataValidationErrors.isEmpty()) {
        return null;
      }
      SelfServiceRegistration request =
          selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
              externalToken, SelfServiceRequestType.PASSWORD_RESET);
      validateRequestState(request, externalToken);
      return request;
    }

    Long requestId =
        this.fromApiJsonHelper.extractLongNamed(
            SelfServiceApiConstants.requestIdParamName, element);
    String authenticationToken =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.authenticationTokenParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.requestIdParamName)
        .value(requestId)
        .notNull()
        .integerGreaterThanZero();
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.authenticationTokenParamName)
        .value(authenticationToken)
        .notBlank()
        .notNull()
        .notExceedingLengthOf(100);
    if (!dataValidationErrors.isEmpty()) {
      return null;
    }
    SelfServiceRegistration request =
        selfServiceRegistrationRepository.getRequestByIdAndAuthenticationToken(
            requestId, authenticationToken, SelfServiceRequestType.PASSWORD_RESET);
    validateRequestState(request, requestId, authenticationToken);
    return request;
  }

  private AppSelfServiceUserClientMapping resolveUserMapping(String username) {
    if (!this.appUserReadPlatformService.isUsernameExist(username)) {
      return null;
    }
    return this.appUserClientMappingRepository.fetchByAppuserUsername(username);
  }

  private boolean matchesExternalId(String externalId, Client client) {
    if (externalId == null) {
      return true;
    }
    String clientExternalId =
        client.getExternalId() == null ? null : client.getExternalId().getValue();
    return externalId.equals(clientExternalId);
  }

  private String extractExternalId(JsonElement element) {
    String externalId =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.externalIdParamName, element);
    if (externalId != null) {
      return externalId;
    }
    return this.fromApiJsonHelper.extractStringNamed(
        SelfServiceApiConstants.externalIDParamName, element);
  }

  private void validateRequestState(SelfServiceRegistration request, String externalToken) {
    if (request == null) {
      throw new SelfServiceRegistrationNotFoundException(externalToken);
    }
    if (request.isConsumed() || request.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.request.token.invalid",
          "The supplied self-service token is expired or already used.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName,
          externalToken);
    }
  }

  private void validateRequestState(
      SelfServiceRegistration request, Long requestId, String authenticationToken) {
    if (request == null) {
      throw new SelfServiceRegistrationNotFoundException(requestId, authenticationToken);
    }
    if (request.isConsumed() || request.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.request.token.invalid",
          "The supplied self-service token is expired or already used.",
          SelfServiceApiConstants.authenticationTokenParamName,
          authenticationToken);
    }
  }

  private String encodePassword(String rawPassword) {
    return platformPasswordEncoder.encode(new RawPlatformUser(rawPassword));
  }

  public void sendAuthorizationToken(
      SelfServiceRegistration selfServiceRegistration, Boolean isEmailAuthenticationMode) {
    if (isEmailAuthenticationMode) {
      sendAuthorizationMail(selfServiceRegistration);
    } else {
      sendAuthorizationMessage(selfServiceRegistration);
    }
  }

  private void trySendAuthorizationToken(
      SelfServiceRegistration selfServiceRegistration, boolean isEmailAuthenticationMode) {
    try {
      sendAuthorizationToken(selfServiceRegistration, isEmailAuthenticationMode);
    } catch (RuntimeException e) {
      log.error(
          "Failed to deliver self-service {} token for request {}",
          selfServiceRegistration.getRequestType(),
          selfServiceRegistration.getId(),
          e);
      // Swallowed intentionally: the request is persisted for audit/retry, and the API
      // must always return 200 to avoid leaking user-existence information.
    }
  }

  private void sendAuthorizationMessage(SelfServiceRegistration selfServiceRegistration) {
    Collection<SmsProviderData> smsProviders =
        this.smsCampaignDropdownReadPlatformService.retrieveSmsProviders();
    if (smsProviders.isEmpty()) {
      throw new PlatformDataIntegrityException(
          "error.msg.mobile.service.provider.not.available",
          "Mobile service provider not available.");
    }
    Long providerId = new ArrayList<>(smsProviders).get(0).getId();
    final String message =
        "Hola  "
            + selfServiceRegistration.getFirstName()
            + ","
            + "\n\nCódigo de Autorización : "
            + selfServiceRegistration.getExternalAuthorizationToken();
    String externalId = null;
    Group group = null;
    Staff staff = null;
    SmsCampaign smsCampaign = null;
    boolean isNotification = false;
    SmsMessage smsMessage =
        SmsMessage.instance(
            externalId,
            group,
            selfServiceRegistration.getClient(),
            staff,
            SmsMessageStatusType.PENDING,
            message,
            selfServiceRegistration.getMobileNumber(),
            smsCampaign,
            isNotification);
    smsMessage = this.smsMessageRepository.save(smsMessage);
    try {
      this.smsMessageScheduledJobService.sendTriggeredMessage(
          new ArrayList<>(Arrays.asList(smsMessage)), providerId);
      smsMessage.setStatusType(SmsMessageStatusType.SENT.getValue());
      this.smsMessageRepository.save(smsMessage);
    } catch (Exception e) {
      smsMessage.setStatusType(SmsMessageStatusType.FAILED.getValue());
      this.smsMessageRepository.save(smsMessage);
      throw e;
    }
  }

  private void sendAuthorizationMail(SelfServiceRegistration selfServiceRegistration) {
    Locale locale = LocaleContextHolder.getLocale();
    final String subject = this.registrationMessageSource.getMessage("email.subject", null, locale);
    Context ctx = new Context(locale);
    ctx.setVariable("firstName", selfServiceRegistration.getFirstName());
    ctx.setVariable("authToken", selfServiceRegistration.getExternalAuthorizationToken());
    final String htmlBody = this.registrationTemplateEngine.process("authorization-email", ctx);
    final EmailDetail emailDetail =
        new EmailDetail(
            subject,
            htmlBody,
            selfServiceRegistration.getEmail(),
            selfServiceRegistration.getFirstName());
    this.selfServicePluginEmailService.sendFormattedEmail(emailDetail);
  }
}
