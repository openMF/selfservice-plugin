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
    log.info(
        "requestEnrollment START userId={}, username={}, mobileNumber={}",
        user.getId(),
        user.getUsername(),
        mobileNumber);

    if (mobileNumber == null || !mobileNumber.matches("\\d{8}")) {
      log.info(
          "requestEnrollment rejected: invalid mobileNumber format (null or not 8 digits). value={}",
          mobileNumber);
      throw new IllegalArgumentException("Invalid SINPE Móvil phone number. Must be 8 digits.");
    }

    var existingVerified =
        sinpeRepository
            .findByAppSelfServiceUserIdAndMobileNumber(user.getId(), mobileNumber)
            .filter(SelfServiceSinpeEnrollment::isVerified);
    if (existingVerified.isPresent()) {
      log.info(
          "requestEnrollment: already verified enrollment exists for userId={}, mobileNumber={}, enrollmentId={}. Returning early.",
          user.getId(),
          mobileNumber,
          existingVerified.get().getId());
      return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
    }
    log.info(
        "requestEnrollment: no verified enrollment found for userId={}, mobileNumber={}",
        user.getId(),
        mobileNumber);

    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);
    log.info(
        "requestEnrollment: generated OTP (len={}), expiry={}",
        otp.length(),
        expiry);

    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    log.info(
        "requestEnrollment: resolved clientId={}, accountNumber={}",
        client.getId(),
        client.getAccountNumber());

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
    log.info(
        "requestEnrollment: SelfServiceRegistration saved id={}, requestType={}, mobileNumber={}, expiry={}",
        request.getId(),
        SelfServiceRequestType.SINPE_ENROLLMENT,
        mobileNumber,
        expiry);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);
    boolean emailMode = determineMode(user.getEmail(), mobileNumber);
    log.info(
        "requestEnrollment: publishing SINPE_ENROLLMENT_OTP notification emailMode={}, hasEmail={}, hasMobile={}",
        emailMode,
        StringUtils.isNotBlank(user.getEmail()),
        StringUtils.isNotBlank(mobileNumber));

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

    log.info("requestEnrollment END userId={}, mobileNumber={}", user.getId(), mobileNumber);
    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult confirmEnrollment(String mobileNumber, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    log.info(
        "confirmEnrollment START userId={}, username={}, mobileNumber={}, otpPresent={}, otpLen={}",
        user.getId(),
        user.getUsername(),
        mobileNumber,
        StringUtils.isNotBlank(otp),
        otp != null ? otp.length() : 0);

    Long clientId = user.getAppUserClientMappings().iterator().next().getClient().getId();
    log.info("confirmEnrollment: looking up registration for clientId={}, requestType=SINPE_ENROLLMENT", clientId);

    SelfServiceRegistration request =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                clientId,
                SelfServiceRequestType.SINPE_ENROLLMENT,
                otp)
            .orElse(null);

    if (request == null) {
      log.info("confirmEnrollment: no SelfServiceRegistration found for clientId={} and provided OTP", clientId);
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    boolean consumed = request.isConsumed();
    boolean expired = request.isExpired(DateUtils.getLocalDateTimeOfSystem());
    log.info(
        "confirmEnrollment: found registration id={}, consumed={}, expired={}, registrationMobile={}",
        request.getId(),
        consumed,
        expired,
        request.getMobileNumber());

    if (consumed || expired) {
      log.info(
          "confirmEnrollment: OTP rejected (consumed={}, expired={}) for registrationId={}",
          consumed,
          expired,
          request.getId());
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    if (!request.getMobileNumber().equals(mobileNumber)) {
      log.info(
          "confirmEnrollment: phone mismatch. requestMobile={}, providedMobile={}",
          request.getMobileNumber(),
          mobileNumber);
      throw new IllegalArgumentException("Phone number mismatch.");
    }

    request.markConsumed();
    registrationRepository.saveAndFlush(request);
    log.info("confirmEnrollment: registration id={} marked as consumed", request.getId());

    SelfServiceSinpeEnrollment enrollment =
        sinpeRepository
            .findByAppSelfServiceUserIdAndMobileNumber(user.getId(), mobileNumber)
            .orElse(
                new SelfServiceSinpeEnrollment(
                    user.getId(), request.getClient().getId(), mobileNumber));

    boolean wasNew = enrollment.getId() == null;
    enrollment.markAsVerified();
    sinpeRepository.saveAndFlush(enrollment);
    log.info(
        "confirmEnrollment: enrollment {} id={}, userId={}, clientId={}, mobileNumber={}, verified=true",
        wasNew ? "created" : "updated",
        enrollment.getId(),
        user.getId(),
        request.getClient().getId(),
        mobileNumber);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("mobileNumber", mobileNumber);
    boolean emailMode = determineMode(user.getEmail(), mobileNumber);
    log.info(
        "confirmEnrollment: publishing SINPE_ENROLLMENT_SUCCESS notification emailMode={}",
        emailMode);

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

    log.info(
        "confirmEnrollment END userId={}, enrollmentId={}, mobileNumber={}",
        user.getId(),
        enrollment.getId(),
        mobileNumber);
    return new CommandProcessingResultBuilder().withEntityId(enrollment.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult createSubscription(SinpeSubscriptionRequest request, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    log.info(
        "createSubscription START userId={}, phoneNumber={}, customerName={}, ibanPresent={}, otpPresent={}, otpLen={}",
        user.getId(),
        request != null ? request.getPhoneNumber() : null,
        request != null ? request.getCustomerName() : null,
        request != null && StringUtils.isNotBlank(request.getIban()),
        StringUtils.isNotBlank(otp),
        otp != null ? otp.length() : 0);

    validateOtp(request.getPhoneNumber(), otp);
    log.info("createSubscription: OTP validated, calling external API createSubscription");

    sinpeExternalApiClient.createSubscription(request);
    log.info("createSubscription: external API createSubscription completed for phone={}", request.getPhoneNumber());

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("phoneNumber", request.getPhoneNumber());
    contextData.put("customerName", request.getCustomerName());
    contextData.put("iban", request.getIban());
    log.info("createSubscription: publishing SINPE_SUBSCRIPTION_CREATED notification");

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

    log.info("createSubscription END userId={}, phoneNumber={}", user.getId(), request.getPhoneNumber());
    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult editSubscription(
      SinpeSubscriptionEditRequest request, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    log.info(
        "editSubscription START userId={}, phoneNumber={}, otpPresent={}, otpLen={}",
        user.getId(),
        request != null ? request.getPhoneNumber() : null,
        StringUtils.isNotBlank(otp),
        otp != null ? otp.length() : 0);

    validateOtp(request.getPhoneNumber(), otp);
    log.info("editSubscription: OTP validated, calling external API editSubscription");

    sinpeExternalApiClient.editSubscription(request);
    log.info("editSubscription: external API editSubscription completed for phone={}", request.getPhoneNumber());

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("phoneNumber", request.getPhoneNumber());
    log.info("editSubscription: publishing SINPE_SUBSCRIPTION_UPDATED notification");

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

    log.info("editSubscription END userId={}, phoneNumber={}", user.getId(), request.getPhoneNumber());
    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult deleteSubscription(String phoneNumber, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    log.info(
        "deleteSubscription START userId={}, phoneNumber={}, otpPresent={}, otpLen={}",
        user.getId(),
        phoneNumber,
        StringUtils.isNotBlank(otp),
        otp != null ? otp.length() : 0);

    validateOtp(phoneNumber, otp);
    log.info("deleteSubscription: OTP validated, calling external API deleteSubscription");

    sinpeExternalApiClient.deleteSubscription(phoneNumber);
    log.info("deleteSubscription: external API deleteSubscription completed for phone={}", phoneNumber);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("phoneNumber", phoneNumber);
    log.info("deleteSubscription: publishing SINPE_SUBSCRIPTION_DELETED notification");

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

    log.info("deleteSubscription END userId={}, phoneNumber={}", user.getId(), phoneNumber);
    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  /**
   * Validates that the provided OTP is valid, not expired, and matches the target phone number.
   * Note: We do not consume the OTP here to allow it to be reused for subsequent edit/delete
   * operations within its validity period.
   */
  private void validateOtp(String mobileNumber, String otp) {
    log.info(
        "validateOtp START mobileNumber={}, otpPresent={}, otpLen={}",
        mobileNumber,
        StringUtils.isNotBlank(otp),
        otp != null ? otp.length() : 0);

    if (StringUtils.isBlank(otp)) {
      log.info("validateOtp: OTP is blank");
      throw new IllegalArgumentException("OTP is required for this operation.");
    }

    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Long clientId = user.getAppUserClientMappings().iterator().next().getClient().getId();
    log.info(
        "validateOtp: looking up registration clientId={}, requestType=SINPE_ENROLLMENT",
        clientId);

    SelfServiceRegistration request =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                clientId, SelfServiceRequestType.SINPE_ENROLLMENT, otp)
            .orElse(null);

    if (request == null) {
      log.info("validateOtp: no registration found for clientId={} and provided OTP", clientId);
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    boolean expired = request.isExpired(DateUtils.getLocalDateTimeOfSystem());
    log.info(
        "validateOtp: found registration id={}, expired={}, registrationMobile={}, consumed={}",
        request.getId(),
        expired,
        request.getMobileNumber(),
        request.isConsumed());

    if (expired) {
      log.info("validateOtp: OTP expired for registrationId={}", request.getId());
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    if (!request.getMobileNumber().equals(mobileNumber)) {
      log.info(
          "validateOtp: phone mismatch registrationMobile={}, providedMobile={}",
          request.getMobileNumber(),
          mobileNumber);
      throw new IllegalArgumentException("Phone number mismatch for the provided OTP.");
    }

    log.info("validateOtp END OK registrationId={}, mobileNumber={}", request.getId(), mobileNumber);
  }

  private boolean determineMode(String email, String mobileNumber) {
    boolean hasEmail = StringUtils.isNotBlank(email);
    boolean hasMobile = StringUtils.isNotBlank(mobileNumber);
    if (hasEmail && !hasMobile) {
      log.info("determineMode: email only -> emailMode=true");
      return true;
    }
    if (hasMobile && !hasEmail) {
      log.info("determineMode: mobile only -> emailMode=false");
      return false;
    }
    String pref =
        env.getProperty("fineract.selfservice.notification.login.delivery-preference", "email");
    boolean emailMode = "email".equalsIgnoreCase(pref);
    log.info(
        "determineMode: both present, preference='{}' -> emailMode={}",
        pref,
        emailMode);
    return emailMode;
  }
}