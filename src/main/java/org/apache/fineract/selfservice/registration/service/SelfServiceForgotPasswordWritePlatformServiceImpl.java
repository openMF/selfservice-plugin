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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceForgotPasswordWritePlatformServiceImpl
    implements SelfServiceForgotPasswordWritePlatformService {

  private final SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  private final FromJsonHelper fromApiJsonHelper;
  private final SelfServiceRegistrationReadPlatformService selfServiceRegistrationReadPlatformService;
  private final AppSelfServiceUserRepository appSelfServiceUserRepository;
  private final PasswordValidationPolicyRepository passwordValidationPolicyRepository;
  private final PlatformPasswordEncoder platformPasswordEncoder;
  private final SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

  @Override
  @Transactional
  public SelfServiceRegistration createForgotPasswordRequest(String apiRequestBodyAsJson) {
    // CORRECCIÓN 1: Parsear JSON primero, luego extraer el username
    JsonElement jsonElement = JsonParser.parseString(apiRequestBodyAsJson);
    String username =
        fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.usernameParamName, jsonElement);

    if (StringUtils.isBlank(username)) {
      log.warn("Password reset request rejected: username is missing or blank");
      throw new IllegalArgumentException("Username is required to request a password reset.");
    }

    AppSelfServiceUser user = appSelfServiceUserRepository.findAppSelfServiceUserByName(username);
    if (user == null) {
      log.warn("Password reset request rejected: user '{}' not found", username);
      return null;
    }

    String email = user.getEmail();
    String mobileNumber = extractMobileNumber(user);

    if (StringUtils.isBlank(email) && StringUtils.isBlank(mobileNumber)) {
      log.warn(
          "Password reset request for user '{}' cannot be processed: no email or mobile number available",
          username);
      return null;
    }

    String token = selfServiceAuthorizationTokenService.generateToken();
    LocalDateTime expiry = selfServiceAuthorizationTokenService.calculateExpiry(LocalDateTime.now());

    Client client =
        user.getAppUserClientMappings() != null && !user.getAppUserClientMappings().isEmpty()
            ? user.getAppUserClientMappings().iterator().next().getClient()
            : null;

    // CORRECCIÓN 2: Usar null para middlename (AppSelfServiceUser no tiene getMiddlename())
    SelfServiceRegistration request =
        SelfServiceRegistration.instance(
            client,
            client != null ? client.getAccountNumber() : null,
            user.getFirstname(),
            null, // middlename no disponible en AppSelfServiceUser
            user.getLastname(),
            mobileNumber,
            email,
            token,
            token,
            username,
            "PASSWORD_RESET",
            SelfServiceRequestType.PASSWORD_RESET,
            expiry);

    selfServiceRegistrationRepository.saveAndFlush(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", token);
    contextData.put("expirationMinutes", 10);
    contextData.put("username", username);

    boolean emailMode = determinePreferredMode(email, mobileNumber);

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
        "Password reset token generated for user '{}'. Token will be delivered through enabled channels.",
        username);

    return request;
  }

  @Override
  @Transactional
  public CommandProcessingResult renewPassword(String apiRequestBodyAsJson) {
    // CORRECCIÓN 3: Parsear JSON primero, luego extraer los campos
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
      throw new IllegalArgumentException(
          "Password, repeatPassword, and externalAuthenticationToken are required.");
    }

    if (!password.equals(repeatPassword)) {
      throw new IllegalArgumentException("Passwords do not match.");
    }

    PasswordValidationPolicy policy =
        passwordValidationPolicyRepository.findActivePasswordValidationPolicy();
    if (policy != null && StringUtils.isNotBlank(policy.getRegex())) {
      if (!password.matches(policy.getRegex())) {
        throw new IllegalArgumentException(
            "Password does not meet the required complexity policy: " + policy.getDescription());
      }
    }

    SelfServiceRegistration request =
        selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            externalToken, SelfServiceRequestType.PASSWORD_RESET);

    if (request == null) {
      throw new IllegalArgumentException("Invalid or expired reset token.");
    }

    if (request.isConsumed()) {
      throw new IllegalArgumentException("Reset token has already been used.");
    }

    if (request.isExpired(DateUtils.getLocalDateTimeOfSystem())) {
      throw new IllegalArgumentException("Reset token has expired. Please request a new one.");
    }

    AppSelfServiceUser user =
        appSelfServiceUserRepository.findAppSelfServiceUserByName(request.getUsername());
    if (user == null) {
      throw new IllegalArgumentException("User not found for this reset token.");
    }

    // CORRECCIÓN 4: encode() solo acepta PlatformUser, no (user, password)
    // Primero actualizamos la contraseña en el objeto user
    user.updatePassword(password);
    // Luego codificamos usando el encoder
    String encodedPassword = platformPasswordEncoder.encode(user);
    // Actualizamos con la contraseña codificada
    user.updatePassword(encodedPassword);
    user.updatePasswordResetRequired(false);
    appSelfServiceUserRepository.saveAndFlush(user);

    request.markConsumed();
    selfServiceRegistrationRepository.saveAndFlush(request);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("username", user.getUsername());

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
            determinePreferredMode(user.getEmail(), extractMobileNumber(user)),
            null,
            LocaleContextHolder.getLocale(),
            contextData));

    log.info("Password successfully renewed for user '{}'", user.getUsername());

    return new CommandProcessingResultBuilder().withEntityId(user.getId()).build();
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

  private boolean determinePreferredMode(String email, String mobileNumber) {
    boolean hasEmail = StringUtils.isNotBlank(email);
    boolean hasMobile = StringUtils.isNotBlank(mobileNumber);

    if (hasEmail && !hasMobile) {
      return true;
    }
    if (hasMobile && !hasEmail) {
      return false;
    }

    String pref =
        env.getProperty("fineract.selfservice.notification.login.delivery-preference", "email");
    return "email".equalsIgnoreCase(pref);
  }
}