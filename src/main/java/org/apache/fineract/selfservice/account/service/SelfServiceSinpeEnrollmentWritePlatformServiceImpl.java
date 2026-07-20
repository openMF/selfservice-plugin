/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionRequest;
import org.apache.fineract.selfservice.account.domain.SelfServiceSinpeEnrollment;
import org.apache.fineract.selfservice.account.domain.SelfServiceSinpeEnrollmentRepository;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceSinpeEnrollmentWritePlatformServiceImpl
    implements SelfServiceSinpeEnrollmentWritePlatformService {

  private final PlatformSelfServiceSecurityContext context;
  private final SelfServiceRegistrationRepository registrationRepository;
  private final SelfServiceSinpeEnrollmentRepository sinpeRepository;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;
  private final SinpeExternalApiClient sinpeExternalApiClient;

  @Override
  @Transactional
  public CommandProcessingResult requestEnrollment(String mobileNumber) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    if (mobileNumber == null || !mobileNumber.matches("\\d{8}")) {
      throw new IllegalArgumentException("Invalid SINPE Móvil phone number. Must be 8 digits.");
    }

    if (sinpeRepository
        .findByAppSelfServiceUserIdAndMobileNumber(user.getId(), mobileNumber)
        .filter(SelfServiceSinpeEnrollment::isVerified)
        .isPresent()) {
      return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
    }

    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);

    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    SelfServiceRegistration request =
        SelfServiceRegistration.instance(
            client,
            client.getAccountNumber(),
            client.getFirstname(),
            client.getMiddlename(),
            client.getLastname(),
            mobileNumber,
            user.getEmail(),
            otp,
            otp,
            user.getUsername(),
            "SINPE_OTP",
            SelfServiceRequestType.SINPE_ENROLLMENT,
            expiry);
    registrationRepository.saveAndFlush(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);

    boolean emailMode = determineMode(user.getEmail(), mobileNumber);

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.SINPE_ENROLLMENT_OTP,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            mobileNumber,
            emailMode,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult confirmEnrollment(String mobileNumber, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    SelfServiceRegistration request =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                user.getAppUserClientMappings().iterator().next().getClient().getId(),
                SelfServiceRequestType.SINPE_ENROLLMENT,
                otp)
            .orElse(null);

    if (request == null
        || request.isConsumed()
        || request.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    if (!request.getMobileNumber().equals(mobileNumber)) {
      throw new IllegalArgumentException("Phone number mismatch.");
    }

    request.markConsumed();
    registrationRepository.saveAndFlush(request);

    SelfServiceSinpeEnrollment enrollment =
        sinpeRepository
            .findByAppSelfServiceUserIdAndMobileNumber(user.getId(), mobileNumber)
            .orElse(
                new SelfServiceSinpeEnrollment(
                    user.getId(), request.getClient().getId(), mobileNumber));

    enrollment.markAsVerified();
    sinpeRepository.saveAndFlush(enrollment);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("mobileNumber", mobileNumber);

    boolean emailMode = determineMode(user.getEmail(), mobileNumber);

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.SINPE_ENROLLMENT_SUCCESS,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            mobileNumber,
            emailMode,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(enrollment.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult createSubscription(SinpeSubscriptionRequest request, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    // Validate OTP before calling external API
    validateOtp(request.getPhoneNumber(), otp);

    sinpeExternalApiClient.createSubscription(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("phoneNumber", request.getPhoneNumber());
    contextData.put("customerName", request.getCustomerName());
    contextData.put("iban", request.getIban());

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_CREATED,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            request.getPhoneNumber(),
            false,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult editSubscription(
      SinpeSubscriptionEditRequest request, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    // Validate OTP before calling external API
    validateOtp(request.getPhoneNumber(), otp);

    sinpeExternalApiClient.editSubscription(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("phoneNumber", request.getPhoneNumber());

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_UPDATED,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            request.getPhoneNumber(),
            false,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult deleteSubscription(String phoneNumber, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    // Validate OTP before calling external API
    validateOtp(phoneNumber, otp);

    sinpeExternalApiClient.deleteSubscription(phoneNumber);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("phoneNumber", phoneNumber);

    applicationEventPublisher.publishEvent(
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.SINPE_SUBSCRIPTION_DELETED,
            user.getId(),
            user.getFirstname(),
            user.getLastname(),
            user.getUsername(),
            user.getEmail(),
            phoneNumber,
            false,
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  /**
   * Validates that the provided OTP is valid, not expired, and matches the target phone number.
   * Note: We do not consume the OTP here to allow it to be reused for subsequent edit/delete
   * operations within its validity period.
   */
  private void validateOtp(String mobileNumber, String otp) {
    if (StringUtils.isBlank(otp)) {
      throw new IllegalArgumentException("OTP is required for this operation.");
    }

    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Long clientId = user.getAppUserClientMappings().iterator().next().getClient().getId();

    SelfServiceRegistration request =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                clientId, SelfServiceRequestType.SINPE_ENROLLMENT, otp)
            .orElse(null);

    if (request == null || request.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    if (!request.getMobileNumber().equals(mobileNumber)) {
      throw new IllegalArgumentException("Phone number mismatch for the provided OTP.");
    }
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
}
