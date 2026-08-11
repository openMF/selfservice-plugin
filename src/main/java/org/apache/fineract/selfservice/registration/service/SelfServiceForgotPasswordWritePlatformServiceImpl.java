/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service forgot-password / renew-password flows.
 *
 * <p><b>Date/time policy (multi-tenant):</b> token creation and expiry checks use {@link
 * TransactionDateUtil#getCurrentTenantLocalDateTime()}, aligned with registration, transfer, SINPE,
 * and token-purge services. System {@link LocalDateTime#now()} and {@code
 * DateUtils#getLocalDateTimeOfSystem()} must not be used here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceForgotPasswordWritePlatformServiceImpl
    implements SelfServiceForgotPasswordWritePlatformService {

  private final SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  private final FromJsonHelper fromApiJsonHelper;
  private final AppSelfServiceUserRepository appSelfServiceUserRepository;
  private final PasswordValidationPolicyRepository passwordValidationPolicyRepository;
  private final PlatformPasswordEncoder platformPasswordEncoder;
  private final SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

  /** Centralized multi-tenant date/time utility for token expiry and validation. */
  private final TransactionDateUtil transactionDateUtil;

  private final NotificationDeliveryModeUtil notificationDeliveryModeUtil;

  /** Cache manager to bypass notification rate-limits for critical security alerts. */
  private final NotificationCooldownCache notificationCooldownCache;

  @Override
  @Transactional
  public SelfServiceRegistration createForgotPasswordRequest(String apiRequestBodyAsJson) {
    JsonElement jsonElement = JsonParser.parseString(apiRequestBodyAsJson);
    String username =
        fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.usernameParamName, jsonElement);

    if (StringUtils.isBlank(username)) {
      log.warn("Password reset request rejected: username is missing or blank");
      throwValidationError(
          "error.msg.password.reset.username.required",
          "Username is required to request a password reset.",
          SelfServiceApiConstants.usernameParamName);
    }

    AppSelfServiceUser user = appSelfServiceUserRepository.findAppSelfServiceUserByName(username);
    if (user == null) {
      log.warn("Password reset request rejected: user '{}' not found", username);
      // Intentionally return null to avoid user enumeration (existing behaviour)
      return null;
    }

    String email = user.getEmail();
    String mobileNumber = extractMobileNumber(user);
    if (StringUtils.isBlank(email) && StringUtils.isBlank(mobileNumber)) {
      log.warn(
          "Password reset request for user '{}' cannot be processed: no email or mobile number"
              + " available",
          username);
      return null;
    }

    String token = selfServiceAuthorizationTokenService.generateToken();
    // Tenant-aware clock — never LocalDateTime.now() or system DateUtils
    LocalDateTime createdAt = transactionDateUtil.getCurrentTenantLocalDateTime();
    LocalDateTime expiry = selfServiceAuthorizationTokenService.calculateExpiry(createdAt);

    Client client =
        user.getAppUserClientMappings() != null && !user.getAppUserClientMappings().isEmpty()
            ? user.getAppUserClientMappings().iterator().next().getClient()
            : null;

    SelfServiceRegistration request =
        SelfServiceRegistration.instance(
            client,
            client != null ? client.getAccountNumber() : null,
            user.getFirstname(),
            null, // middlename not available on AppSelfServiceUser
            user.getLastname(),
            mobileNumber,
            email,
            token,
            token,
            username,
            "PASSWORD_RESET",
            SelfServiceRequestType.PASSWORD_RESET,
            expiry,
            createdAt);

    selfServiceRegistrationRepository.saveAndFlush(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", token);
    contextData.put("expirationMinutes", 10);
    contextData.put("username", username);

    boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);

    // Release cooldown to ensure the critical reset token is delivered immediately
    releasePasswordResetRequestedCooldown(user);

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.PASSWORD_RESET_REQUESTED,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            email,
            mobileNumber,
            emailMode,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    log.info(
        "Password reset token generated for user '{}'. Token will be delivered through enabled"
            + " channels. createdAt={}, expiresAt={}",
        username,
        createdAt,
        expiry);

    return request;
  }

  @Override
  @Transactional
  public CommandProcessingResult renewPassword(String apiRequestBodyAsJson) {
    JsonElement jsonElement = JsonParser.parseString(apiRequestBodyAsJson);

    String password =
        fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.passwordParamName, jsonElement);
    String repeatPassword =
        fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.repeatPasswordParamName, jsonElement);
    String externalToken =
        fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.externalAuthenticationTokenParamName, jsonElement);

    if (StringUtils.isBlank(password)
        || StringUtils.isBlank(repeatPassword)
        || StringUtils.isBlank(externalToken)) {
      throwValidationError(
          "error.msg.password.reset.required.fields",
          "Password, repeatPassword, and externalAuthenticationToken are required.",
          "password");
    }

    if (!password.equals(repeatPassword)) {
      throwValidationError(
          "error.msg.password.reset.passwords.do.not.match",
          "Passwords do not match.",
          SelfServiceApiConstants.repeatPasswordParamName);
    }

    PasswordValidationPolicy policy =
        passwordValidationPolicyRepository.findActivePasswordValidationPolicy();
    if (policy != null && StringUtils.isNotBlank(policy.getRegex())) {
      if (!password.matches(policy.getRegex())) {
        throwValidationError(
            "error.msg.password.reset.policy.violation",
            "Password does not meet the required complexity policy: " + policy.getDescription(),
            SelfServiceApiConstants.passwordParamName);
      }
    }

    SelfServiceRegistration request =
        selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            externalToken, SelfServiceRequestType.PASSWORD_RESET);

    if (request == null) {
      throwValidationError(
          "error.msg.password.reset.token.invalid",
          "Invalid or expired reset token.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName);
    }

    if (request.isConsumed()) {
      throwValidationError(
          "error.msg.password.reset.token.already.used",
          "Reset token has already been used.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName);
    }

    if (request.isExpired(transactionDateUtil.getCurrentTenantLocalDateTime())) {
      throwValidationError(
          "error.msg.password.reset.token.expired",
          "Reset token has expired. Please request a new one.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName);
    }

    AppSelfServiceUser user =
        appSelfServiceUserRepository.findAppSelfServiceUserByName(request.getUsername());
    if (user == null) {
      throwValidationError(
          "error.msg.password.reset.user.not.found",
          "User not found for this reset token.",
          SelfServiceApiConstants.externalAuthenticationTokenParamName);
    }

    user.updatePassword(password);
    String encodedPassword = platformPasswordEncoder.encode(user);
    user.updatePassword(encodedPassword);
    user.updatePasswordResetRequired(false);
    appSelfServiceUserRepository.saveAndFlush(user);

    request.markConsumed();
    selfServiceRegistrationRepository.saveAndFlush(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("username", user.getUsername());

    boolean emailMode =
        notificationDeliveryModeUtil.determineMode(user.getEmail(), extractMobileNumber(user));

    // Release cooldown to ensure the security alert is delivered immediately
    releasePasswordRenewedCooldown(user);

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.PASSWORD_RENEWED,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            extractMobileNumber(user),
            emailMode,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    log.info("Password successfully renewed for user '{}'", user.getUsername());

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  /**
   * Throws a {@link PlatformApiDataValidationException} so the client receives a proper Fineract
   * validation error (typically HTTP 400) instead of a generic 500.
   */
  private void throwValidationError(
      final String globalisationMessageCode,
      final String defaultUserMessage,
      final String parameterName) {

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    dataValidationErrors.add(
        ApiParameterError.parameterError(
            globalisationMessageCode, defaultUserMessage, parameterName));

    throw new PlatformApiDataValidationException(dataValidationErrors);
  }

  private void releasePasswordResetRequestedCooldown(AppSelfServiceUser user) {
    try {
      String cacheKey =
          SelfServiceNotificationEvent.Type.PASSWORD_RESET_REQUESTED.name() + ":" + user.getId();
      notificationCooldownCache.release(cacheKey);
      log.info(
          "FORGOT PASSWORD: Released PASSWORD_RESET_REQUESTED cooldown for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to release PASSWORD_RESET_REQUESTED cooldown (non-fatal)", e);
    }
  }

  private void releasePasswordRenewedCooldown(AppSelfServiceUser user) {
    try {
      String cacheKey =
          SelfServiceNotificationEvent.Type.PASSWORD_RENEWED.name() + ":" + user.getId();
      notificationCooldownCache.release(cacheKey);
      log.info("FORGOT PASSWORD: Released PASSWORD_RENEWED cooldown for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to release PASSWORD_RENEWED cooldown (non-fatal)", e);
    }
  }

  private String extractMobileNumber(AppSelfServiceUser user) {
    if (user == null || user.getAppUserClientMappings() == null) {
      return null;
    }
    return user.getAppUserClientMappings().stream()
        .map(AppSelfServiceUserClientMapping::getClient)
        .filter(Objects::nonNull)
        .map(Client::getMobileNo)
        .filter(StringUtils::isNotBlank)
        .findFirst()
        .orElse(null);
  }
}