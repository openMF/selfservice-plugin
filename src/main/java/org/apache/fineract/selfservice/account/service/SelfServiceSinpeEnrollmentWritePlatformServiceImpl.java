package org.apache.fineract.selfservice.account.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.client.domain.Client;
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

  @Override
  @Transactional
  public CommandProcessingResult requestEnrollment(String mobileNumber) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    // 1. Validate SINPE Móvil format (Costa Rica: 8 digits)
    if (mobileNumber == null || !mobileNumber.matches("\\d{8}")) {
      throw new IllegalArgumentException("Invalid SINPE Móvil phone number. Must be 8 digits.");
    }

    // 2. Check if already verified
    if (sinpeRepository
        .findByAppSelfServiceUserIdAndMobileNumber(user.getId(), mobileNumber)
        .filter(SelfServiceSinpeEnrollment::isVerified)
        .isPresent()) {
      return new CommandProcessingResultBuilder()
          .withEntityId(user.getId())
          .build(); // Already enrolled
    }

    // 3. Generate 6-digit OTP
    String otp = String.format("%06d", new SecureRandom().nextInt(999999));
    LocalDateTime expiry = DateUtils.getLocalDateTimeOfSystem().plusMinutes(10);

    // 4. Save OTP in SelfServiceRegistration (Reusing existing audit/token table)
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

    // 5. Publish Notification Event (Forces SMS/WhatsApp for OTP)
    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", 10);

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
            false, // Force SMS/WhatsApp mode for OTP
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
  }

  @Override
  @Transactional
  public CommandProcessingResult confirmEnrollment(String mobileNumber, String otp) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();

    // 1. Find pending OTP request
    SelfServiceRegistration request =
        registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                user.getAppUserClientMappings().iterator().next().getClient().getId(),
                SelfServiceRequestType.SINPE_ENROLLMENT,
                otp)
            .orElse(null); // Added .orElse(null) to match your null-check logic below

    if (request == null
        || request.isConsumed()
        || request.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      throw new IllegalArgumentException("Invalid or expired OTP.");
    }

    if (!request.getMobileNumber().equals(mobileNumber)) {
      throw new IllegalArgumentException("Phone number mismatch.");
    }

    // 2. Mark OTP as consumed
    request.markConsumed();
    registrationRepository.saveAndFlush(request);

    // 3. Save/Update Verified SINPE Enrollment
    SelfServiceSinpeEnrollment enrollment =
        sinpeRepository
            .findByAppSelfServiceUserIdAndMobileNumber(user.getId(), mobileNumber)
            .orElse(
                new SelfServiceSinpeEnrollment(
                    user.getId(), request.getClient().getId(), mobileNumber));

    enrollment.markAsVerified();
    sinpeRepository.saveAndFlush(enrollment);

    // 4. Publish Success Notification
    Map<String, Object> contextData = new HashMap<>();
    contextData.put("mobileNumber", mobileNumber);

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
            false, // Send success via SMS/WhatsApp
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    return new CommandProcessingResultBuilder().withEntityId(enrollment.getId()).build();
  }
}
