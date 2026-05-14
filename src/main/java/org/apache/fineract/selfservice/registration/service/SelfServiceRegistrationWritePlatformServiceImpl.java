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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.infrastructure.campaigns.sms.data.SmsProviderData;
import org.apache.fineract.infrastructure.campaigns.sms.domain.SmsCampaign;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;
import org.apache.fineract.infrastructure.configuration.domain.NotificationMessage;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.EmailDetail;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.security.domain.BasicPasswordEncodablePlatformUser;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.infrastructure.sms.domain.SmsMessage;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageStatusType;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.registration.exception.SelfServiceEnrollmentConflictException;
import org.apache.fineract.selfservice.registration.exception.SelfServiceRegistrationNotFoundException;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.SelfServiceUserDomainService;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.domain.Role;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.apache.fineract.useradministration.exception.RoleNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

@Slf4j
@RequiredArgsConstructor
public class SelfServiceRegistrationWritePlatformServiceImpl
    implements SelfServiceRegistrationWritePlatformService {

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
  private final ClientWritePlatformService clientWritePlatformService;
  private final Environment env;
  private final PlatformPasswordEncoder platformPasswordEncoder;
  private final AppSelfServiceUserRepository appSelfServiceUserRepository;
  private final SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  private final ITemplateEngine registrationTemplateEngine;
  private final MessageSource registrationMessageSource;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final ExternalNotificationSystemClient externalNotificationSystemClient;
  private NotificationCredentialsData notificationCredentialsData;

  @Override
  public SelfServiceRegistration createRegistrationRequest(String apiRequestBodyAsJson) {
    Gson gson = new Gson();
    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("user");
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap,
        apiRequestBodyAsJson,
        SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS);
    JsonElement element = gson.fromJson(apiRequestBodyAsJson.toString(), JsonElement.class);

    String accountNumber =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.accountNumberParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.accountNumberParamName)
        .value(accountNumber)
        .notNull()
        .notBlank()
        .notExceedingLengthOf(100);

    String firstName =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.firstNameParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.firstNameParamName)
        .value(firstName)
        .notBlank()
        .notExceedingLengthOf(100);

    String middleName =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.middleNameParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.middleNameParamName)
        .value(middleName)
        .ignoreIfNull()
        .notExceedingLengthOf(100);

    String lastName =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.lastNameParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.lastNameParamName)
        .value(lastName)
        .notBlank()
        .notExceedingLengthOf(100);

    String username =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.usernameParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.usernameParamName)
        .value(username)
        .notBlank()
        .notExceedingLengthOf(100);

    // validate password policy
    String password =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.passwordParamName, element);
    final PasswordValidationPolicy validationPolicy =
        this.passwordValidationPolicy.findActivePasswordValidationPolicy();
    final String regex = validationPolicy.getRegex();
    final String description = validationPolicy.getDescription();
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .matchesRegularExpression(regex, description)
        .notExceedingLengthOf(100);

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

    String email =
        this.fromApiJsonHelper.extractStringNamed(SelfServiceApiConstants.emailParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.emailParamName)
        .value(email)
        .notNull()
        .notBlank()
        .notExceedingLengthOf(100);

    boolean isEmailAuthenticationMode =
        authenticationMode.equalsIgnoreCase(SelfServiceApiConstants.emailModeParamName);

    boolean isAnyAuthenticationMode =
        authenticationMode.equalsIgnoreCase(SelfServiceApiConstants.anyModeParamName);

    String mobileNumber = null;
    if (!isEmailAuthenticationMode) {
      mobileNumber =
          this.fromApiJsonHelper.extractStringNamed(
              SelfServiceApiConstants.mobileNumberParamName, element);
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.mobileNumberParamName)
          .value(mobileNumber)
          .notNull()
          .validatePhoneNumber();
    }
    validateForDuplicateUsername(username);

    throwExceptionIfValidationError(
        dataValidationErrors,
        accountNumber,
        firstName,
        middleName,
        lastName,
        mobileNumber,
        isEmailAuthenticationMode);

    String authenticationToken = selfServiceAuthorizationTokenService.generateToken();
    LocalDateTime createdAt = DateUtils.getLocalDateTimeOfSystem();
    Client client = this.clientRepository.getClientByAccountNumber(accountNumber);
    SelfServiceRegistration selfServiceRegistration =
        SelfServiceRegistration.instance(
            client,
            accountNumber,
            firstName,
            middleName,
            lastName,
            mobileNumber,
            email,
            authenticationToken,
            authenticationToken,
            username,
            encodePassword(password),
            SelfServiceRequestType.REGISTRATION,
            selfServiceAuthorizationTokenService.calculateExpiry(createdAt));
    this.selfServiceRegistrationRepository.saveAndFlush(selfServiceRegistration);
    sendAuthorizationToken(
        selfServiceRegistration, isEmailAuthenticationMode, isAnyAuthenticationMode);
    return selfServiceRegistration;
  }

  public void validateForDuplicateUsername(String username) {
    boolean isDuplicateUserName = this.appUserReadPlatformService.isUsernameExist(username);
    if (isDuplicateUserName) {
      final StringBuilder defaultMessageBuilder =
          new StringBuilder("User with username ").append(username).append(" already exists.");
      throw new PlatformDataIntegrityException(
          "error.msg.user.duplicate.username",
          defaultMessageBuilder.toString(),
          SelfServiceApiConstants.usernameParamName,
          username);
    }
  }

  public void sendAuthorizationToken(
      SelfServiceRegistration selfServiceRegistration,
      Boolean isEmailAuthenticationMode,
      Boolean isAnyAuthenticationMode) {

    if (isEmailAuthenticationMode) {
      sendAuthorizationMail(selfServiceRegistration);
    } else {
      sendAuthorizationMessage(selfServiceRegistration, isAnyAuthenticationMode);
    }
  }

  private void sendAuthorizationMessage(
      SelfServiceRegistration selfServiceRegistration, Boolean isAnyAuthenticationMode) {

    notificationCredentialsData = externalNotificationSystemClient.resolveNotificationCredentials();

    if (!isAnyAuthenticationMode) {
      Collection<SmsProviderData> smsProviders =
          this.smsCampaignDropdownReadPlatformService.retrieveSmsProviders();
      if (!smsProviders.isEmpty()) {
        Long providerId = new ArrayList<>(smsProviders).get(0).getId();
        Locale locale = LocaleContextHolder.getLocale();
        final String message =
            this.registrationMessageSource.getMessage(
                "sms.message",
                new Object[] {
                  selfServiceRegistration.getFirstName(),
                  selfServiceRegistration.getId(),
                  selfServiceRegistration.getAuthenticationToken()
                },
                locale);
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
    } else if (notificationCredentialsData.isEnabled()) {

      final String message =
          selfServiceRegistration.getFirstName()
              + " use this token for activate your account "
              + selfServiceRegistration.getAuthenticationToken();

      NotificationMessage notificationMessage = new NotificationMessage();

      notificationMessage.setEmail(selfServiceRegistration.getEmail());
      notificationMessage.setMobile(selfServiceRegistration.getMobileNumber());
      notificationMessage.setText(message);

      try {

        externalNotificationSystemClient.sendPostRequest(notificationMessage);
      } catch (Exception e) {
        log.error("Error when sending to external system ", e.getMessage());
        e.printStackTrace();
      }
    }
  }

  private void sendAuthorizationMail(SelfServiceRegistration selfServiceRegistration) {
    Locale locale = LocaleContextHolder.getLocale();
    final String subject = this.registrationMessageSource.getMessage("email.subject", null, locale);
    Context ctx = new Context(locale);
    ctx.setVariable("firstName", selfServiceRegistration.getFirstName());
    ctx.setVariable("requestId", selfServiceRegistration.getId());
    ctx.setVariable("authToken", selfServiceRegistration.getAuthenticationToken());
    final String htmlBody = this.registrationTemplateEngine.process("authorization-email", ctx);
    final EmailDetail emailDetail =
        new EmailDetail(
            subject,
            htmlBody,
            selfServiceRegistration.getEmail(),
            selfServiceRegistration.getFirstName());
    this.selfServicePluginEmailService.sendFormattedEmail(emailDetail);
  }

  private void throwExceptionIfValidationError(
      final List<ApiParameterError> dataValidationErrors,
      String accountNumber,
      String firstName,
      String middleName,
      String lastName,
      String mobileNumber,
      boolean isEmailAuthenticationMode) {
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
    boolean isClientExist =
        this.selfServiceRegistrationReadPlatformService.isClientExist(
            accountNumber,
            firstName,
            middleName,
            lastName,
            mobileNumber,
            isEmailAuthenticationMode);
    if (!isClientExist) {
      throw new ClientNotFoundException();
    }
  }

  @Transactional(rollbackFor = Exception.class)
  @Override
  public AppSelfServiceUser createSelfServiceUser(String apiRequestBodyAsJson) {
    JsonCommand command = null;
    String username = null;
    try {
      Gson gson = new Gson();
      final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
      final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
      final DataValidatorBuilder baseDataValidator =
          new DataValidatorBuilder(dataValidationErrors).resource("user");
      this.fromApiJsonHelper.checkForUnsupportedParameters(
          typeOfMap,
          apiRequestBodyAsJson,
          SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS);
      JsonElement element = gson.fromJson(apiRequestBodyAsJson.toString(), JsonElement.class);

      SelfServiceRegistration selfServiceRegistration =
          resolveRegistrationRequest(element, baseDataValidator, dataValidationErrors);
      if (!dataValidationErrors.isEmpty()) {
        throw new PlatformApiDataValidationException(dataValidationErrors);
      }

      command = JsonCommand.fromJsonElement(selfServiceRegistration.getId(), element);
      username = selfServiceRegistration.getUsername();
      Client client = selfServiceRegistration.getClient();
      final boolean passwordNeverExpire = true;
      final boolean isSelfServiceUser = true;
      final Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
      authorities.add(
          new SimpleGrantedAuthority("DUMMY_ROLE_NOT_USED_OR_PERSISTED_TO_AVOID_EXCEPTION"));
      final Set<Role> allRoles = new HashSet<>();
      Role role = this.roleRepository.getRoleByName(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
      if (role != null) {
        allRoles.add(role);
      } else {
        throw new RoleNotFoundException(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
      }
      List<Client> clients = new ArrayList<>();
      User user =
          new User(
              selfServiceRegistration.getUsername(),
              selfServiceRegistration.getPassword(),
              authorities);
      AppSelfServiceUser appUser =
          new AppSelfServiceUser(
              client.getOffice(),
              user,
              allRoles,
              selfServiceRegistration.getEmail(),
              client.getFirstname(),
              client.getLastname(),
              null,
              passwordNeverExpire,
              isSelfServiceUser,
              clients,
              null);
      appUser.updatePassword(selfServiceRegistration.getPassword());
      selfServiceRegistration.markConsumed();
      this.selfServiceRegistrationRepository.saveAndFlush(selfServiceRegistration);
      this.appSelfServiceUserRepository.saveAndFlush(appUser);
      this.appUserClientMappingRepository.saveClientUserMapping(appUser.getId(), client.getId());
      return appUser;

    } catch (final JpaSystemException | DataIntegrityViolationException dve) {
      handleDataIntegrityIssues(command, dve.getMostSpecificCause(), dve, username);
      return null;
    } catch (final PersistenceException | AuthenticationServiceException dve) {
      Throwable throwable = ExceptionUtils.getRootCause(dve);
      handleDataIntegrityIssues(command, throwable != null ? throwable : dve, dve, username);
      return null;
    }
  }

  /**
   * Chooses between the legacy token-confirmation flow and the one-shot enrollment flow based on
   * the request payload.
   *
   * @param apiRequestBodyAsJson JSON request body containing either confirmation fields or
   *     self-enrollment data
   * @return the created self-service user
   */
  @Transactional(rollbackFor = Exception.class)
  @Override
  public AppSelfServiceUser createSelfServiceUserOrEnroll(String apiRequestBodyAsJson) {
    return createSelfServiceUser(apiRequestBodyAsJson);
  }

  private void handleDataIntegrityIssues(
      final JsonCommand command, final Throwable realCause, final Exception dve, String username) {
    if (realCause.getMessage().contains("'username_org'")) {
      final StringBuilder defaultMessageBuilder =
          new StringBuilder("User with username ").append(username).append(" already exists.");
      throw new PlatformDataIntegrityException(
          "error.msg.user.duplicate.username",
          defaultMessageBuilder.toString(),
          "username",
          username);
    }
    throw ErrorHandler.getMappable(
        dve,
        "error.msg.unknown.data.integrity.issue",
        "Unknown data integrity issue with resource.");
  }

  /**
   * Creates a client and linked self-service user in a single transaction using the configured
   * audit user for privileged client creation.
   *
   * @param apiRequestBodyAsJson JSON request body containing self-enrollment data
   * @return the created self-service user linked to the newly created client
   */
  @Transactional(rollbackFor = Exception.class)
  @Override
  public SelfServiceRegistration selfEnroll(String apiRequestBodyAsJson) {
    Gson gson = new Gson();
    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("user");
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, apiRequestBodyAsJson, SelfServiceApiConstants.SELF_ENROLLMENT_DATA_PARAMETERS);
    JsonElement element = gson.fromJson(apiRequestBodyAsJson.toString(), JsonElement.class);

    String username =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.usernameParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.usernameParamName)
        .value(username)
        .notBlank()
        .notExceedingLengthOf(100);

    String password =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.passwordParamName, element);
    final PasswordValidationPolicy validationPolicy =
        this.passwordValidationPolicy.findActivePasswordValidationPolicy();
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .matchesRegularExpression(validationPolicy.getRegex(), validationPolicy.getDescription())
        .notExceedingLengthOf(100);

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
            SelfServiceApiConstants.mobileModeParamName,
            SelfServiceApiConstants.anyModeParamName);

    String email =
        this.fromApiJsonHelper.extractStringNamed(SelfServiceApiConstants.emailParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.emailParamName)
        .value(email)
        .notExceedingLengthOf(100);

    boolean isEmailAuthenticationMode =
        authenticationMode != null
            && authenticationMode.equalsIgnoreCase(SelfServiceApiConstants.emailModeParamName);
    boolean isAnyAuthenticationMode =
        authenticationMode != null
            && authenticationMode.equalsIgnoreCase(SelfServiceApiConstants.anyModeParamName);
    if (isEmailAuthenticationMode) {
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.emailParamName)
          .value(email)
          .notBlank();
    }

    String mobileNumber =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.mobileNumberParamName, element);
    if (!isEmailAuthenticationMode) {
      // Mobile auth: phone is required and must be valid
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.mobileNumberParamName)
          .value(mobileNumber)
          .notBlank()
          .validatePhoneNumber();
    } else if (mobileNumber != null) {
      // Email auth: phone is optional but must be valid if provided,
      // because Fineract's ClientDataValidator always validates mobileNo format.
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.mobileNumberParamName)
          .value(mobileNumber)
          .validatePhoneNumber();
    }

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    validateForDuplicateUsernameForEnrollment(username);

    JsonObject originalJson = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    JsonObject sanitizedJson = normalizeSelfEnrollmentClientPayload(originalJson);
    JsonElement parsedSanitizedElement = sanitizedJson;

    String auditUsername = env.getProperty("fineract.selfservice.enrollment.audit-user", "mifos");
    final Authentication previousAuth = SecurityContextHolder.getContext().getAuthentication();
    AppUser auditUser = resolveEnrollmentAuditUser(auditUsername);
    Authentication auth =
        new UsernamePasswordAuthenticationToken(auditUser, null, auditUser.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(auth);

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (previousAuth != null) {
              SecurityContextHolder.getContext().setAuthentication(previousAuth);
            } else {
              SecurityContextHolder.clearContext();
            }
          }
        });

    try {
      JsonCommand command =
          JsonCommand.fromJsonElement(null, parsedSanitizedElement, this.fromApiJsonHelper);
      CommandProcessingResult result = clientWritePlatformService.createClient(command);
      Long newClientId = result.getResourceId();

      Client client = clientRepository.findOneWithNotFoundDetection(newClientId);
      Role ssRole = roleRepository.getRoleByName(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
      if (ssRole == null) {
        throw new RoleNotFoundException(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
      }
      Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
      authorities.add(
          new SimpleGrantedAuthority("DUMMY_ROLE_NOT_USED_OR_PERSISTED_TO_AVOID_EXCEPTION"));

      // Create user as DISABLED — will be enabled on enrollment confirmation
      User springConfigUser = new User(username, password, false, true, true, true, authorities);
      AppSelfServiceUser appUser =
          new AppSelfServiceUser(
              client.getOffice(),
              springConfigUser,
              Collections.singleton(ssRole),
              email,
              client.getFirstname(),
              client.getLastname(),
              null,
              true,
              true,
              Collections.emptyList(),
              null);

      userDomainService.create(appUser, false);
      appUserClientMappingRepository.saveClientUserMapping(appUser.getId(), client.getId());

      // Create enrollment confirmation token
      String authenticationToken = selfServiceAuthorizationTokenService.generateToken();
      LocalDateTime createdAt = DateUtils.getLocalDateTimeOfSystem();
      SelfServiceRegistration registration =
          SelfServiceRegistration.instance(
              client,
              client.getAccountNumber(),
              client.getFirstname(),
              client.getMiddlename(),
              client.getLastname(),
              mobileNumber,
              email,
              authenticationToken,
              authenticationToken,
              username,
              encodePassword(password),
              SelfServiceRequestType.ENROLLMENT,
              selfServiceAuthorizationTokenService.calculateExpiry(createdAt));
      this.selfServiceRegistrationRepository.saveAndFlush(registration);
      try {
        sendAuthorizationToken(registration, isEmailAuthenticationMode, isAnyAuthenticationMode);
      } catch (RuntimeException e) {
        log.error(
            "Failed to deliver enrollment confirmation token for clientId={}, userId={}",
            newClientId,
            appUser.getId(),
            e);
        // The enrollment record is already persisted and can be retried or inspected by operators.
      }

      log.info(
          "Self-enrollment created (pending confirmation): clientId={}, userId={}",
          newClientId,
          appUser.getId());
      return registration;

    } catch (PlatformDataIntegrityException pde) {
      throw translateEnrollmentConflict(pde);
    } catch (DataIntegrityViolationException dve) {
      throw translateEnrollmentFailure(dve);
    } catch (PersistenceException dve) {
      throw translateEnrollmentFailure(dve);
    }
  }

  /**
   * Confirms a pending self-enrollment by validating the provided authentication token, enabling
   * the associated user account, and publishing a {@code USER_ACTIVATED} notification event after
   * the transaction commits.
   *
   * <p>The JSON payload must contain either an {@code externalAuthenticationToken} or the legacy
   * {@code requestId}/{@code authenticationToken} pair.
   *
   * @param apiRequestBodyAsJson confirmation request JSON
   * @return the enabled {@link AppSelfServiceUser}
   * @throws PlatformApiDataValidationException if required parameters are missing or invalid
   * @throws SelfServiceRegistrationNotFoundException if no matching enrollment request exists
   * @throws PlatformDataIntegrityException if the token is expired or already used
   */
  @Transactional(rollbackFor = Exception.class)
  @Override
  public AppSelfServiceUser confirmEnrollment(String apiRequestBodyAsJson) {
    Gson gson = new Gson();
    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("user");
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap,
        apiRequestBodyAsJson,
        SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS);
    JsonElement element = gson.fromJson(apiRequestBodyAsJson, JsonElement.class);

    SelfServiceRegistration request =
        resolveEnrollmentRequest(element, baseDataValidator, dataValidationErrors);
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

    // Claim the token atomically before mutating user state so the optimistic
    // lock fires before any irreversible changes are made.
    try {
      request.markConsumed();
      this.selfServiceRegistrationRepository.saveAndFlush(request);
    } catch (OptimisticLockingFailureException e) {
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.request.token.invalid",
          "The supplied self-service token is expired or already used.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName);
    }

    appUser.enable();
    this.appSelfServiceUserRepository.saveAndFlush(appUser);

    org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant capturedTenant = null;
    try {
      capturedTenant =
          org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    final org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant tenantSnapshot =
        capturedTenant;
    final java.util.HashMap<
            org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType,
            java.time.LocalDate>
        businessDatesSnapshot;
    java.util.HashMap<
            org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType,
            java.time.LocalDate>
        tempDates = null;
    try {
      java.util.HashMap<
              org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType,
              java.time.LocalDate>
          dates =
              org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil
                  .getBusinessDates();
      tempDates = dates != null ? new java.util.HashMap<>(dates) : null;
    } catch (IllegalArgumentException e) {
    }
    businessDatesSnapshot = tempDates;

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              applicationEventPublisher.publishEvent(
                  new org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent(
                      SelfServiceRegistrationWritePlatformServiceImpl.this,
                      org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent.Type
                          .USER_ACTIVATED,
                      appUser.getId(),
                      appUser.getFirstname(),
                      appUser.getLastname(),
                      appUser.getUsername(),
                      request.getEmail(),
                      request.getMobileNumber(),
                      isEmailMode(request),
                      null,
                      LocaleContextHolder.getLocale(),
                      tenantSnapshot,
                      businessDatesSnapshot));
            } catch (Exception e) {
              log.warn(
                  "Failed to publish USER_ACTIVATED notification for userId={}",
                  appUser.getId(),
                  e);
            }
          }
        });

    log.info("Self-enrollment confirmed: userId={}", appUser.getId());
    return appUser;
  }

  private SelfServiceRegistration resolveEnrollmentRequest(
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
          this.selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
              externalToken, SelfServiceRequestType.ENROLLMENT);
      validateRequestState(request, externalToken);
      return request;
    }

    Long id =
        this.fromApiJsonHelper.extractLongNamed(
            SelfServiceApiConstants.requestIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.requestIdParamName)
        .value(id)
        .notNull()
        .integerGreaterThanZero();
    String authenticationToken =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.authenticationTokenParamName, element);
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
        this.selfServiceRegistrationRepository.getRequestByIdAndAuthenticationToken(
            id, authenticationToken, SelfServiceRequestType.ENROLLMENT);
    validateRequestState(request, id, authenticationToken);
    return request;
  }

  private AppUser resolveEnrollmentAuditUser(String auditUsername) {
    try {
      Long auditUserId =
          jdbcTemplate.queryForObject(
              "SELECT id FROM m_appuser WHERE username = ?", Long.class, auditUsername);
      if (auditUserId == null) {
        throw new IllegalStateException(
            "Self-enrollment audit user '"
                + auditUsername
                + "' is configured but no matching m_appuser id was returned.");
      }
      return appUserRepository
          .findById(auditUserId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Self-enrollment audit user '"
                          + auditUsername
                          + "' was found in m_appuser lookup but could not be loaded from the repository."));
    } catch (EmptyResultDataAccessException e) {
      throw new IllegalStateException(
          "Configured self-enrollment audit user '"
              + auditUsername
              + "' does not exist. Set fineract.selfservice.enrollment.audit-user to a valid username.",
          e);
    }
  }

  private void validateForDuplicateUsernameForEnrollment(String username) {
    boolean isDuplicateUserName = this.appUserReadPlatformService.isUsernameExist(username);
    if (isDuplicateUserName) {
      throw enrollmentConflict(
          "error.msg.user.duplicate.username", "Username already exists", "username");
    }
  }

  private SelfServiceEnrollmentConflictException enrollmentConflict(
      String code, String message, String parameterName) {
    return new SelfServiceEnrollmentConflictException(code, message, parameterName);
  }

  private RuntimeException translateEnrollmentConflict(PlatformDataIntegrityException exception) {
    String code = exception.getGlobalisationMessageCode();
    if ("error.msg.client.duplicate.mobileNo".equals(code)) {
      return enrollmentConflict(code, exception.getDefaultUserMessage(), "mobileNo");
    }
    if ("error.msg.client.duplicate.email".equals(code)) {
      return enrollmentConflict(code, exception.getDefaultUserMessage(), "email");
    }
    if ("error.msg.user.duplicate.username".equals(code)) {
      return enrollmentConflict(code, exception.getDefaultUserMessage(), "username");
    }
    return exception;
  }

  private RuntimeException translateEnrollmentFailure(Exception exception) {
    Throwable rootCause = ExceptionUtils.getRootCause(exception);
    Throwable mostSpecificCause = rootCause != null ? rootCause : exception;
    String message = mostSpecificCause.getMessage();
    if (message == null) {
      return ErrorHandler.getMappable(
          exception, "error.msg.unknown.data.integrity.issue", "Unknown data integrity issue");
    }

    String normalized = message.toLowerCase();
    if (normalized.contains("username") || normalized.contains("username_org")) {
      return enrollmentConflict(
          "error.msg.user.duplicate.username", "Username already exists", "username");
    }
    if (normalized.contains("mobile_no") || normalized.contains("mobileno")) {
      return enrollmentConflict(
          "error.msg.client.duplicate.mobileNo", "Mobile number already exists", "mobileNo");
    }
    if (normalized.contains("email")) {
      return enrollmentConflict(
          "error.msg.client.duplicate.email", "Email already exists", "email");
    }

    return ErrorHandler.getMappable(
        exception, "error.msg.unknown.data.integrity.issue", "Unknown data integrity issue");
  }

  private JsonObject normalizeSelfEnrollmentClientPayload(JsonObject originalJson) {
    JsonObject sanitizedJson = new JsonObject();

    copyFirstPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.firstnameParamName,
        SelfServiceApiConstants.firstnameParamName,
        SelfServiceApiConstants.firstNameParamName);
    copyFirstPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.middlenameParamName,
        SelfServiceApiConstants.middlenameParamName,
        SelfServiceApiConstants.middleNameParamName);
    copyFirstPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.lastnameParamName,
        SelfServiceApiConstants.lastnameParamName,
        SelfServiceApiConstants.lastNameParamName);
    copyIfPresent(
        originalJson, sanitizedJson, "mobileNo", SelfServiceApiConstants.mobileNumberParamName);
    copyIfPresent(
        originalJson, sanitizedJson, "emailAddress", SelfServiceApiConstants.emailParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.clientTypeIdParamName,
        SelfServiceApiConstants.clientTypeIdParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.clientClassificationIdParamName,
        SelfServiceApiConstants.clientClassificationIdParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.dateOfBirthParamName,
        SelfServiceApiConstants.dateOfBirthParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.genderIdParamName,
        SelfServiceApiConstants.genderIdParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.addressParamName,
        SelfServiceApiConstants.addressParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.datatablesParamName,
        SelfServiceApiConstants.datatablesParamName);
    copyIfPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.familyMembersParamName,
        SelfServiceApiConstants.familyMembersParamName);
    copyFirstPresent(
        originalJson,
        sanitizedJson,
        SelfServiceApiConstants.externalIdParamName,
        SelfServiceApiConstants.externalIdParamName,
        SelfServiceApiConstants.externalIDParamName);

    long officeId =
        Long.parseLong(env.getProperty("fineract.selfservice.enrollment.default-office-id", "1"));
    sanitizedJson.addProperty(SelfServiceApiConstants.officeIdParamName, officeId);

    int legalFormId =
        originalJson.has(SelfServiceApiConstants.legalFormIdParamName)
            ? originalJson.get(SelfServiceApiConstants.legalFormIdParamName).getAsInt()
            : Integer.parseInt(
                env.getProperty("fineract.selfservice.enrollment.default-legal-form-id", "1"));
    sanitizedJson.addProperty(SelfServiceApiConstants.legalFormIdParamName, legalFormId);

    String submittedOnDate =
        stringValueOrDefault(originalJson, SelfServiceApiConstants.submittedOnDateParamName, null);
    String dateFormat =
        stringValueOrDefault(originalJson, SelfServiceApiConstants.dateFormatParamName, null);
    if (StringUtils.isBlank(dateFormat)) {
      if (looksIsoDate(submittedOnDate)) {
        dateFormat = "yyyy-MM-dd";
      } else {
        dateFormat =
            env.getProperty("fineract.selfservice.enrollment.default-date-format", "yyyy-MM-dd");
      }
    }
    sanitizedJson.addProperty(SelfServiceApiConstants.dateFormatParamName, dateFormat);

    String locale =
        stringValueOrDefault(
            originalJson,
            SelfServiceApiConstants.localeParamName,
            env.getProperty("fineract.selfservice.enrollment.default-locale", "en"));
    sanitizedJson.addProperty(SelfServiceApiConstants.localeParamName, locale);

    boolean isActive =
        Boolean.parseBoolean(
            env.getProperty("fineract.selfservice.enrollment.default-active", "false"));
    sanitizedJson.addProperty(SelfServiceApiConstants.activeParamName, isActive);

    String today = java.time.LocalDate.now(java.time.ZoneId.of("UTC")).toString();
    sanitizedJson.addProperty(
        SelfServiceApiConstants.submittedOnDateParamName,
        StringUtils.defaultIfBlank(submittedOnDate, today));
    if (isActive) {
      sanitizedJson.addProperty(SelfServiceApiConstants.activationDateParamName, today);
    }

    return sanitizedJson;
  }

  private void copyIfPresent(
      JsonObject source, JsonObject target, String targetKey, String sourceKey) {
    if (source.has(sourceKey)) {
      target.add(targetKey, source.get(sourceKey));
    }
  }

  private void copyFirstPresent(
      JsonObject source, JsonObject target, String targetKey, String... sourceKeys) {
    for (String sourceKey : sourceKeys) {
      if (source.has(sourceKey)) {
        target.add(targetKey, source.get(sourceKey));
        return;
      }
    }
  }

  private String stringValueOrDefault(JsonObject json, String key, String defaultValue) {
    if (json.has(key) && !json.get(key).isJsonNull()) {
      return json.get(key).getAsString();
    }
    return defaultValue;
  }

  private boolean looksIsoDate(String value) {
    return StringUtils.isNotBlank(value) && value.matches("\\d{4}-\\d{2}-\\d{2}");
  }

  private SelfServiceRegistration resolveRegistrationRequest(
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
          this.selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
              externalToken, SelfServiceRequestType.REGISTRATION);
      validateRequestState(request, externalToken);
      return request;
    }

    Long id =
        this.fromApiJsonHelper.extractLongNamed(
            SelfServiceApiConstants.requestIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.requestIdParamName)
        .value(id)
        .notNull()
        .integerGreaterThanZero();
    String authenticationToken =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.authenticationTokenParamName, element);
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
        this.selfServiceRegistrationRepository.getRequestByIdAndAuthenticationToken(
            id, authenticationToken, SelfServiceRequestType.REGISTRATION);
    validateRequestState(request, id, authenticationToken);
    return request;
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
    return platformPasswordEncoder.encode(
        new BasicPasswordEncodablePlatformUser().setPassword(rawPassword));
  }

  private boolean isEmailMode(SelfServiceRegistration request) {
    boolean hasEmail = StringUtils.isNotBlank(request.getEmail());
    boolean hasMobile = StringUtils.isNotBlank(request.getMobileNumber());

    if (hasEmail && !hasMobile) return true;
    if (hasMobile && !hasEmail) return false;

    String pref =
        env.getProperty("fineract.selfservice.notification.login.delivery-preference", "email");
    return "email".equalsIgnoreCase(pref);
  }
}
