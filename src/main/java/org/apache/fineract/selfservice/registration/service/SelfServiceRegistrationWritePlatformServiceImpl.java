/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.TransactionDateManagementService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.infrastructure.security.domain.BasicPasswordEncodablePlatformUser;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.client.service.ClientIdentifierWritePlatformService;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.selfservice.api.data.TransactionDateRequest;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.registration.exception.SelfServiceEnrollmentConflictException;
import org.apache.fineract.selfservice.registration.exception.SelfServiceRegistrationNotFoundException;
import org.apache.fineract.selfservice.security.service.SelfServiceDeviceFingerprintService;
import org.apache.fineract.selfservice.security.util.DeviceFingerprintUtil;
import org.apache.fineract.selfservice.security.util.DeviceFingerprintUtil.DeviceSignals;
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
  private final ApplicationEventPublisher applicationEventPublisher;

  /** Centralized multi-tenant date/time helpers (LocalDate / LocalDateTime / format). */
  private final TransactionDateUtil transactionDateUtil;

  /**
   * Higher-level date processing for API payloads (validates + formats via TransactionDateUtil).
   * Ensures any client-supplied transaction/submitted dates are normalized tenant-safely before
   * being overridden by policy where required.
   */
  private final TransactionDateManagementService transactionDateManagementService;
  
  private final ClientIdentifierWritePlatformService clientIdentifierWritePlatformService;
  
  private final SelfServiceDeviceFingerprintService deviceFingerprintService;
  
  private static final long DEFAULT_DOCUMENT_TYPE_ID = 1L;

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
    // Tenant-aware clock — never system LocalDateTime.now()
    LocalDateTime createdAt = transactionDateUtil.getCurrentTenantLocalDateTime();

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

    publishEnrollmentTokenEvent(selfServiceRegistration, isEmailAuthenticationMode);

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
  public AppSelfServiceUser createSelfServiceUser(String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
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
      try {
        JsonObject body = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
        DeviceSignals signals = DeviceFingerprintUtil.from(httpRequest, body);
        deviceFingerprintService.registerOrTouch(appUser.getId(), signals, true);
      } catch (Exception e) {
        log.warn("Self-enrollment: could not persist device fingerprint for userId={}", appUser.getId(), e);
      }
      publishUserCreatedEvent(appUser, selfServiceRegistration);
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

  @Transactional(rollbackFor = Exception.class)
  @Override
  public AppSelfServiceUser createSelfServiceUserOrEnroll(String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
    return createSelfServiceUser(apiRequestBodyAsJson, httpRequest);
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

  @Transactional(rollbackFor = Exception.class)
  @Override
  public SelfServiceRegistration selfEnroll(String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
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
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.mobileNumberParamName)
          .value(mobileNumber)
          .notBlank()
          .validatePhoneNumber();
    } else if (mobileNumber != null) {
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
    // CRITICAL: normalize + force tenant dates (override any client-submitted submittedOnDate /
    // activationDate)
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
      createClientIdentifierFromEnrollment(newClientId, originalJson);

      Client client = clientRepository.findOneWithNotFoundDetection(newClientId);
      Role ssRole = roleRepository.getRoleByName(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
      if (ssRole == null) {
        throw new RoleNotFoundException(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
      }
      Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
      authorities.add(
          new SimpleGrantedAuthority("DUMMY_ROLE_NOT_USED_OR_PERSISTED_TO_AVOID_EXCEPTION"));

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
      try {
        JsonObject body = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
        DeviceSignals signals = DeviceFingerprintUtil.from(httpRequest, body);
        deviceFingerprintService.registerOrTouch(appUser.getId(), signals, true);
      } catch (Exception e) {
        log.warn("Self-enrollment: could not persist device fingerprint for userId={}", appUser.getId(), e);
      }

      String authenticationToken = selfServiceAuthorizationTokenService.generateToken();
      LocalDateTime createdAt = transactionDateUtil.getCurrentTenantLocalDateTime();

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

      publishEnrollmentTokenEvent(registration, isEmailAuthenticationMode);

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

    FineractPlatformTenant capturedTenant = null;
    try {
      capturedTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    final FineractPlatformTenant tenantSnapshot = capturedTenant;
    final HashMap<BusinessDateType, LocalDate> businessDatesSnapshot;
    HashMap<BusinessDateType, LocalDate> tempDates = null;
    try {
      HashMap<BusinessDateType, LocalDate> dates = ThreadLocalContextUtil.getBusinessDates();
      tempDates = dates != null ? new HashMap<>(dates) : null;
    } catch (IllegalArgumentException e) {
    }
    businessDatesSnapshot = tempDates;

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              applicationEventPublisher.publishEvent(
                  new SelfServiceNotificationEvent(
                      SelfServiceRegistrationWritePlatformServiceImpl.this,
                      SelfServiceNotificationEvent.Type.USER_ACTIVATED,
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

  private void publishUserCreatedEvent(
      AppSelfServiceUser appUser, SelfServiceRegistration registration) {
    FineractPlatformTenant capturedTenant = null;
    try {
      capturedTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    final FineractPlatformTenant tenantSnapshot = capturedTenant;

    final HashMap<BusinessDateType, LocalDate> businessDatesSnapshot;
    HashMap<BusinessDateType, LocalDate> tempDates = null;
    try {
      HashMap<BusinessDateType, LocalDate> dates = ThreadLocalContextUtil.getBusinessDates();
      tempDates = dates != null ? new HashMap<>(dates) : null;
    } catch (IllegalArgumentException e) {
    }
    businessDatesSnapshot = tempDates;

    Runnable publishTask =
        () -> {
          try {
            applicationEventPublisher.publishEvent(
                new SelfServiceNotificationEvent(
                    SelfServiceRegistrationWritePlatformServiceImpl.this,
                    SelfServiceNotificationEvent.Type.USER_CREATED,
                    appUser.getId(),
                    appUser.getFirstname(),
                    appUser.getLastname(),
                    appUser.getUsername(),
                    registration.getEmail(),
                    registration.getMobileNumber(),
                    isEmailMode(registration),
                    null,
                    LocaleContextHolder.getLocale(),
                    tenantSnapshot,
                    businessDatesSnapshot));
          } catch (Exception e) {
            log.warn(
                "Failed to publish USER_CREATED notification for userId={}", appUser.getId(), e);
          }
        };

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publishTask.run();
            }
          });
    } else {
      publishTask.run();
    }
  }

  private void publishEnrollmentTokenEvent(
      SelfServiceRegistration registration, boolean isEmailMode) {
    FineractPlatformTenant capturedTenant = null;
    try {
      capturedTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    final FineractPlatformTenant tenantSnapshot = capturedTenant;

    final HashMap<BusinessDateType, LocalDate> businessDatesSnapshot;
    HashMap<BusinessDateType, LocalDate> tempDates = null;
    try {
      HashMap<BusinessDateType, LocalDate> dates = ThreadLocalContextUtil.getBusinessDates();
      tempDates = dates != null ? new HashMap<>(dates) : null;
    } catch (IllegalArgumentException e) {
    }
    businessDatesSnapshot = tempDates;

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("requestId", String.valueOf(registration.getId()));
    contextData.put("authCode", registration.getAuthenticationToken());

    Runnable publishTask =
        () -> {
          try {
            applicationEventPublisher.publishEvent(
                new SelfServiceNotificationEvent(
                    SelfServiceRegistrationWritePlatformServiceImpl.this,
                    SelfServiceNotificationEvent.Type.ENROLLMENT_TOKEN,
                    registration.getId(),
                    registration.getFirstName(),
                    registration.getLastName(),
                    registration.getUsername(),
                    registration.getEmail(),
                    registration.getMobileNumber(),
                    isEmailMode,
                    null,
                    LocaleContextHolder.getLocale(),
                    tenantSnapshot,
                    businessDatesSnapshot,
                    contextData));
          } catch (Exception e) {
            log.warn(
                "Failed to publish ENROLLMENT_TOKEN notification for registrationId={}",
                registration.getId(),
                e);
          }
        };

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publishTask.run();
            }
          });
    } else {
      publishTask.run();
    }
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
      if (!dataValidationErrors.isEmpty()) return null;
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
    if (!dataValidationErrors.isEmpty()) return null;
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
      if (auditUserId == null)
        throw new IllegalStateException(
            "Self-enrollment audit user '"
                + auditUsername
                + "' is configured but no matching m_appuser id was returned.");
      return appUserRepository
          .findById(auditUserId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Self-enrollment audit user '"
                          + auditUsername
                          + "' was found in m_appuser lookup but could not be loaded from the"
                          + " repository."));
    } catch (EmptyResultDataAccessException e) {
      throw new IllegalStateException(
          "Configured self-enrollment audit user '"
              + auditUsername
              + "' does not exist. Set fineract.selfservice.enrollment.audit-user to a valid"
              + " username.",
          e);
    }
  }

  private void validateForDuplicateUsernameForEnrollment(String username) {
    boolean isDuplicateUserName = this.appUserReadPlatformService.isUsernameExist(username);
    if (isDuplicateUserName)
      throw enrollmentConflict(
          "error.msg.user.duplicate.username", "Username already exists", "username");
  }

  private SelfServiceEnrollmentConflictException enrollmentConflict(
      String code, String message, String parameterName) {
    return new SelfServiceEnrollmentConflictException(code, message, parameterName);
  }

  private RuntimeException translateEnrollmentConflict(PlatformDataIntegrityException exception) {
    String code = exception.getGlobalisationMessageCode();
    if ("error.msg.client.duplicate.mobileNo".equals(code))
      return enrollmentConflict(code, exception.getDefaultUserMessage(), "mobileNo");
    if ("error.msg.client.duplicate.email".equals(code))
      return enrollmentConflict(code, exception.getDefaultUserMessage(), "email");
    if ("error.msg.user.duplicate.username".equals(code))
      return enrollmentConflict(code, exception.getDefaultUserMessage(), "username");
    return exception;
  }

  private RuntimeException translateEnrollmentFailure(Exception exception) {
    Throwable rootCause = ExceptionUtils.getRootCause(exception);
    Throwable mostSpecificCause = rootCause != null ? rootCause : exception;
    String message = mostSpecificCause.getMessage();
    if (message == null)
      return ErrorHandler.getMappable(
          exception, "error.msg.unknown.data.integrity.issue", "Unknown data integrity issue");
    String normalized = message.toLowerCase();
    if (normalized.contains("username") || normalized.contains("username_org"))
      return enrollmentConflict(
          "error.msg.user.duplicate.username", "Username already exists", "username");
    if (normalized.contains("mobile_no") || normalized.contains("mobileno"))
      return enrollmentConflict(
          "error.msg.client.duplicate.mobileNo", "Mobile number already exists", "mobileNo");
    if (normalized.contains("email"))
      return enrollmentConflict(
          "error.msg.client.duplicate.email", "Email already exists", "email");
    return ErrorHandler.getMappable(
        exception, "error.msg.unknown.data.integrity.issue", "Unknown data integrity issue");
  }

  /**
   * Builds the Fineract client-create payload for self-enrollment.
   *
   * <p><b>Date policy (centralized, multi-tenant):</b>
   *
   * <ul>
   *   <li>{@code submittedOnDate} and {@code activationDate} are <em>always</em> set from the
   *       tenant clock via {@link TransactionDateUtil} / {@link TransactionDateManagementService}.
   *       Any value submitted by the REST client for those fields is discarded.
   *   <li>Personal attributes such as {@code dateOfBirth} remain client-supplied (not overridden).
   * </ul>
   */
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
    // dateOfBirth is a personal attribute — do NOT override with tenant business date
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

    String clientDateFormat =
        stringValueOrDefault(originalJson, SelfServiceApiConstants.dateFormatParamName, null);
    String clientSubmittedOnDate =
        stringValueOrDefault(originalJson, SelfServiceApiConstants.submittedOnDateParamName, null);

    if (StringUtils.isBlank(clientDateFormat)) {
      if (looksIsoDate(clientSubmittedOnDate)) {
        clientDateFormat = "yyyy-MM-dd";
      } else {
        clientDateFormat =
            env.getProperty("fineract.selfservice.enrollment.default-date-format", "yyyy-MM-dd");
      }
    }
    sanitizedJson.addProperty(SelfServiceApiConstants.dateFormatParamName, clientDateFormat);

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

    // --- Centralized date override (ignore any date submitted by the REST API) ---
    // 1) Process optional client date through TransactionDateManagementService for logging /
    //    validation path consistency (multi-tenant OffsetDateTime).
    // 2) Force submittedOnDate / activationDate to tenant "today" formatted for Fineract.
    if (StringUtils.isNotBlank(clientSubmittedOnDate)) {
      log.info(
          "Self-enrollment: discarding client-submitted submittedOnDate='{}' in favour of tenant date",
          clientSubmittedOnDate);
      TransactionDateRequest dateRequest =
          new TransactionDateRequest(clientSubmittedOnDate, clientDateFormat, locale);
      OffsetDateTime processed =
          transactionDateManagementService.processAndValidateTransactionDate(dateRequest);
      log.debug("Client date processed via TransactionDateManagementService → {}", processed);
    }

    final String tenantToday =
        transactionDateUtil.getCurrentDateForFineract(clientDateFormat, locale);

    sanitizedJson.addProperty(SelfServiceApiConstants.submittedOnDateParamName, tenantToday);
    if (isActive) {
      sanitizedJson.addProperty(SelfServiceApiConstants.activationDateParamName, tenantToday);
    }

    log.info(
        "Self-enrollment client payload dates forced to tenant date='{}' (format='{}', locale='{}')",
        tenantToday,
        clientDateFormat,
        locale);

    return sanitizedJson;
  }

  private void copyIfPresent(
      JsonObject source, JsonObject target, String targetKey, String sourceKey) {
    if (source.has(sourceKey)) target.add(targetKey, source.get(sourceKey));
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
    if (json.has(key) && !json.get(key).isJsonNull()) return json.get(key).getAsString();
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
      if (!dataValidationErrors.isEmpty()) return null;
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
    if (!dataValidationErrors.isEmpty()) return null;
    SelfServiceRegistration request =
        this.selfServiceRegistrationRepository.getRequestByIdAndAuthenticationToken(
            id, authenticationToken, SelfServiceRequestType.REGISTRATION);
    validateRequestState(request, id, authenticationToken);
    return request;
  }

  private void validateRequestState(SelfServiceRegistration request, String externalToken) {
    if (request == null) throw new SelfServiceRegistrationNotFoundException(externalToken);
    if (request.isConsumed()
        || request.isExpired(transactionDateUtil.getCurrentTenantLocalDateTime()))
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.request.token.invalid",
          "The supplied self-service token is expired or already used.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName,
          externalToken);
  }

  private void validateRequestState(
      SelfServiceRegistration request, Long requestId, String authenticationToken) {
    if (request == null)
      throw new SelfServiceRegistrationNotFoundException(requestId, authenticationToken);
    if (request.isConsumed()
        || request.isExpired(transactionDateUtil.getCurrentTenantLocalDateTime()))
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.request.token.invalid",
          "The supplied self-service token is expired or already used.",
          SelfServiceApiConstants.authenticationTokenParamName,
          authenticationToken);
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
  
  /**
    * Creates a client identifier from self-enrollment payload.
    *
    * <p>Mapping (required by product):
    * <ul>
    *   <li>{@code documentTypeId} → identifier document type
    *   <li>{@code externalID} / {@code externalId} → {@code documentKey}
    *   <li>optional explicit {@code documentKey} overrides external id for the key
    * </ul>
    *
    * <p>Runs in the same multi-tenant transaction / security context as client create.
    * Failures are non-fatal for enrollment (logged); raise if you prefer hard fail.
    */
   private void createClientIdentifierFromEnrollment(Long clientId, JsonObject originalJson) {
    if (clientId == null || originalJson == null) {
      return;
    }

    Long documentTypeId = extractLong(originalJson, SelfServiceApiConstants.documentTypeIdParamName);
    if (documentTypeId == null) {
      documentTypeId = DEFAULT_DOCUMENT_TYPE_ID;
      log.info(
          "Self-enrollment: documentTypeId missing, using fallback={}",
          DEFAULT_DOCUMENT_TYPE_ID);
    }

    String documentKey = resolveDocumentKey(originalJson);
    if (StringUtils.isBlank(documentKey)) {
      log.debug(
          "Self-enrollment: skipping client identifier (clientId={}): no documentKey/externalID",
          clientId);
      return;
    }

    if (tryAddClientIdentifier(clientId, documentTypeId, documentKey)) {
      return;
    }

    // Invalid code value (e.g. 1648) → retry once with fallback 1
    if (!Long.valueOf(DEFAULT_DOCUMENT_TYPE_ID).equals(documentTypeId)) {
      log.warn(
          "Self-enrollment: documentTypeId={} failed, retrying with fallback={}",
          documentTypeId,
          DEFAULT_DOCUMENT_TYPE_ID);
      tryAddClientIdentifier(clientId, DEFAULT_DOCUMENT_TYPE_ID, documentKey);
    }
  }

  /**
   * @return true if identifier was created successfully
   */
  private boolean tryAddClientIdentifier(Long clientId, Long documentTypeId, String documentKey) {
    try {
      JsonObject identifierJson = new JsonObject();
      identifierJson.addProperty("documentTypeId", documentTypeId);
      identifierJson.addProperty("documentKey", documentKey);
      identifierJson.addProperty("status", "Active");
      identifierJson.addProperty("description", "");

      JsonCommand identifierCommand =
          JsonCommand.fromJsonElement(clientId, identifierJson, this.fromApiJsonHelper);

      CommandProcessingResult identifierResult =
          clientIdentifierWritePlatformService.addClientIdentifier(clientId, identifierCommand);

      log.info(
          "Self-enrollment: client identifier created clientId={}, documentTypeId={}, documentKey={}, resourceId={}",
          clientId,
          documentTypeId,
          documentKey,
          identifierResult != null ? identifierResult.getResourceId() : null);
      return true;
    } catch (Exception e) {
      log.error(
          "Self-enrollment: failed to create client identifier for clientId={}, documentTypeId={}, documentKey={}",
          clientId,
          documentTypeId,
          documentKey,
          e);
      return false;
    }
  }

   private String resolveDocumentKey(JsonObject originalJson) {
     // Explicit documentKey wins if clients send both
     String explicitKey =
         stringValueOrDefault(originalJson, SelfServiceApiConstants.documentKeyParamName, null);
     if (StringUtils.isNotBlank(explicitKey)) {
       return explicitKey.trim();
     }
     String externalId =
         stringValueOrDefault(originalJson, SelfServiceApiConstants.externalIdParamName, null);
     if (StringUtils.isBlank(externalId)) {
       externalId =
           stringValueOrDefault(originalJson, SelfServiceApiConstants.externalIDParamName, null);
     }
     return StringUtils.isNotBlank(externalId) ? externalId.trim() : null;
   }

   private Long extractLong(JsonObject json, String key) {
     if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
       return null;
     }
     try {
       if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isNumber()) {
         return json.get(key).getAsLong();
       }
       String asString = json.get(key).getAsString();
       if (StringUtils.isBlank(asString)) {
         return null;
       }
       return Long.valueOf(asString.trim());
     } catch (Exception e) {
       log.warn("Could not parse long for key '{}': {}", key, e.getMessage());
       return null;
     }
   }
}