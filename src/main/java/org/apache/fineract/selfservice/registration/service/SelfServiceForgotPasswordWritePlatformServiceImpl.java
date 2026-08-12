/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.registration.data.SelfServiceForgotPasswordDataValidator;
import org.apache.fineract.selfservice.registration.data.SelfServiceForgotPasswordDataValidator.RenewPasswordData;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.registration.exception.SelfServicePasswordResetNoContactException;
import org.apache.fineract.selfservice.registration.exception.SelfServicePasswordResetTokenException;
import org.apache.fineract.selfservice.registration.exception.SelfServiceUserNotFoundException;
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
 * <p><b>Date/time policy (multi-tenant):</b> token creation and expiry always use {@link
 * TransactionDateUtil#getCurrentTenantLocalDateTime()} so that each tenant's configured timezone is
 * respected.
 *
 * <p>All input validation is performed by {@link SelfServiceForgotPasswordDataValidator} and
 * surfaced via {@code PlatformApiDataValidationException}. Business-rule failures use
 * {@code AbstractPlatformDomainRuleException} subclasses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfServiceForgotPasswordWritePlatformServiceImpl
    implements SelfServiceForgotPasswordWritePlatformService {

  private final SelfServiceForgotPasswordDataValidator dataValidator;
  private final AppSelfServiceUserRepository appSelfServiceUserRepository;
  private final SelfServiceRegistrationRepository selfServiceRegistrationRepository;
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

  // -------------------------------------------------------------------------
  // Request password reset
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public SelfServiceRegistration createForgotPasswordRequest(final String apiRequestBodyAsJson) {

    // Fineract-style validation → PlatformApiDataValidationException on failure
    final String username = dataValidator.validateAndExtractUsername(apiRequestBodyAsJson);

    // Multi-tenant: repository query runs against the current tenant's datasource
    final AppSelfServiceUser user =
        appSelfServiceUserRepository.findAppSelfServiceUserByName(username);
    if (user == null) {
      log.warn("Password reset request rejected: user '{}' not found", username);
      throw new SelfServiceUserNotFoundException(username);
    }

    final String email = user.getEmail();
    final String mobileNumber = extractMobileNumber(user);

    if (StringUtils.isBlank(email) && StringUtils.isBlank(mobileNumber)) {
      log.warn(
          "Password reset request for user '{}' cannot be processed: no email or mobile number"
              + " available",
          username);
      throw new SelfServicePasswordResetNoContactException(username);
    }

    final String token = selfServiceAuthorizationTokenService.generateToken();
    // Tenant-aware clock — never LocalDateTime.now() or system DateUtils
    final LocalDateTime createdAt = transactionDateUtil.getCurrentTenantLocalDateTime();
    final LocalDateTime expiry = selfServiceAuthorizationTokenService.calculateExpiry(createdAt);

    final Client client =
        user.getAppUserClientMappings() != null && !user.getAppUserClientMappings().isEmpty()
            ? user.getAppUserClientMappings().iterator().next().getClient()
            : null;

    final SelfServiceRegistration request =
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

    final Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", token);
    contextData.put("expirationMinutes", 10);
    contextData.put("username", username);

    final boolean emailMode =
        notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);

    // Release cooldown so the critical reset token is delivered immediately
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

  // -------------------------------------------------------------------------
  // Renew password
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public CommandProcessingResult renewPassword(final String apiRequestBodyAsJson) {

    // Fineract-style validation → PlatformApiDataValidationException on failure
    final RenewPasswordData data = dataValidator.validateForRenew(apiRequestBodyAsJson);

    final PasswordValidationPolicy policy =
        passwordValidationPolicyRepository.findActivePasswordValidationPolicy();
    dataValidator.validatePasswordAgainstPolicy(data.password(), policy);

    final SelfServiceRegistration request =
        selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            data.externalAuthenticationToken(), SelfServiceRequestType.PASSWORD_RESET);

    if (request == null) {
      throw SelfServicePasswordResetTokenException.invalid();
    }
    if (request.isConsumed()) {
      throw SelfServicePasswordResetTokenException.consumed();
    }
    if (request.isExpired(transactionDateUtil.getCurrentTenantLocalDateTime())) {
      throw SelfServicePasswordResetTokenException.expired();
    }

    final AppSelfServiceUser user =
        appSelfServiceUserRepository.findAppSelfServiceUserByName(request.getUsername());
    if (user == null) {
      // Extremely rare – token exists but user was deleted
      throw new SelfServiceUserNotFoundException(request.getUsername());
    }

    user.updatePassword(data.password());
    final String encodedPassword = platformPasswordEncoder.encode(user);
    user.updatePassword(encodedPassword);
    user.updatePasswordResetRequired(false);
    appSelfServiceUserRepository.saveAndFlush(user);

    request.markConsumed();
    selfServiceRegistrationRepository.saveAndFlush(request);

    final Map<String, Object> contextData = new HashMap<>();
    contextData.put("username", user.getUsername());

    final boolean emailMode =
        notificationDeliveryModeUtil.determineMode(user.getEmail(), extractMobileNumber(user));

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

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private void releasePasswordResetRequestedCooldown(final AppSelfServiceUser user) {
    try {
      final String cacheKey =
          SelfServiceNotificationEvent.Type.PASSWORD_RESET_REQUESTED.name() + ":" + user.getId();
      notificationCooldownCache.release(cacheKey);
      log.info(
          "FORGOT PASSWORD: Released PASSWORD_RESET_REQUESTED cooldown for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to release PASSWORD_RESET_REQUESTED cooldown (non-fatal)", e);
    }
  }

  private void releasePasswordRenewedCooldown(final AppSelfServiceUser user) {
    try {
      final String cacheKey =
          SelfServiceNotificationEvent.Type.PASSWORD_RENEWED.name() + ":" + user.getId();
      notificationCooldownCache.release(cacheKey);
      log.info("FORGOT PASSWORD: Released PASSWORD_RENEWED cooldown for user {}", user.getId());
    } catch (Exception e) {
      log.warn("Failed to release PASSWORD_RENEWED cooldown (non-fatal)", e);
    }
  }

  private String extractMobileNumber(final AppSelfServiceUser user) {
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