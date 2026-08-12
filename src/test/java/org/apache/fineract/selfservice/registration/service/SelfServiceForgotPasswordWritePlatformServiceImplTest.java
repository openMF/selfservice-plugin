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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class SelfServiceForgotPasswordWritePlatformServiceImplTest {

  @Mock private SelfServiceForgotPasswordDataValidator dataValidator;
  @Mock private AppSelfServiceUserRepository appSelfServiceUserRepository;
  @Mock private SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  @Mock private PasswordValidationPolicyRepository passwordValidationPolicyRepository;
  @Mock private PlatformPasswordEncoder platformPasswordEncoder;
  @Mock private SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private Environment env;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private NotificationDeliveryModeUtil notificationDeliveryModeUtil;
  @Mock private NotificationCooldownCache notificationCooldownCache;

  private SelfServiceForgotPasswordWritePlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new SelfServiceForgotPasswordWritePlatformServiceImpl(
            dataValidator,
            appSelfServiceUserRepository,
            selfServiceRegistrationRepository,
            passwordValidationPolicyRepository,
            platformPasswordEncoder,
            selfServiceAuthorizationTokenService,
            applicationEventPublisher,
            env,
            transactionDateUtil,
            notificationDeliveryModeUtil,
            notificationCooldownCache);

    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  // -------------------------------------------------------------------------
  // createForgotPasswordRequest
  // -------------------------------------------------------------------------

  @Test
  void createForgotPasswordRequest_persistsPasswordResetRequest() {
    // Given
    when(dataValidator.validateAndExtractUsername("{\"username\":\"jdoe\"}")).thenReturn("jdoe");
    when(selfServiceAuthorizationTokenService.generateToken()).thenReturn("123456");

    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 13, 12, 0, 0);
    LocalDateTime expectedExpiry = LocalDateTime.of(2026, 4, 13, 12, 0, 30);
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(createdAt);
    when(selfServiceAuthorizationTokenService.calculateExpiry(any())).thenReturn(expectedExpiry);

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
    when(appUser.getId()).thenReturn(1L);
    when(appUser.getEmail()).thenReturn("test@test.com");
    when(appUser.getFirstname()).thenReturn("John");
    when(appUser.getLastname()).thenReturn("Doe");
    when(appUser.getUsername()).thenReturn("jdoe");

    Client client = mock(Client.class);
    when(client.getAccountNumber()).thenReturn("0001");
    when(client.getMobileNo()).thenReturn("5551234567");

    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getClient()).thenReturn(client);
    Set<AppSelfServiceUserClientMapping> mappings = new HashSet<>();
    mappings.add(mapping);
    when(appUser.getAppUserClientMappings()).thenReturn(mappings);

    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("jdoe")).thenReturn(appUser);
    when(notificationDeliveryModeUtil.determineMode(any(), any())).thenReturn(true);

    // When
    SelfServiceRegistration result =
        service.createForgotPasswordRequest("{\"username\":\"jdoe\"}");

    // Then
    assertNotNull(result);
    assertEquals(SelfServiceRequestType.PASSWORD_RESET, result.getRequestType());
    assertEquals("123456", result.getExternalAuthorizationToken());
    assertEquals(expectedExpiry, result.getExpiresAt());

    verify(selfServiceRegistrationRepository)
        .saveAndFlush(argThat(request -> expectedExpiry.equals(request.getExpiresAt())));

    ArgumentCaptor<SelfServiceNotificationEvent> eventCaptor =
        ArgumentCaptor.forClass(SelfServiceNotificationEvent.class);
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    SelfServiceNotificationEvent event = eventCaptor.getValue();
    assertEquals(SelfServiceNotificationEvent.Type.PASSWORD_RESET_REQUESTED, event.getType());
    assertEquals("jdoe", event.getUsername());
    assertEquals("test@test.com", event.getEmail());
    assertEquals("5551234567", event.getMobileNumber());
  }

  @Test
  void createForgotPasswordRequest_throwsUserNotFoundWhenUserMissing() {
    when(dataValidator.validateAndExtractUsername("{\"username\":\"nonexistent\"}"))
        .thenReturn("nonexistent");
    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("nonexistent"))
        .thenReturn(null);

    assertThrows(
        SelfServiceUserNotFoundException.class,
        () -> service.createForgotPasswordRequest("{\"username\":\"nonexistent\"}"));

    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  void createForgotPasswordRequest_throwsNoContactWhenUserHasNoEmailOrMobile() {
    when(dataValidator.validateAndExtractUsername("{\"username\":\"jdoe\"}")).thenReturn("jdoe");

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
    when(appUser.getEmail()).thenReturn(null);
    when(appUser.getAppUserClientMappings()).thenReturn(new HashSet<>());
    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("jdoe")).thenReturn(appUser);

    assertThrows(
        SelfServicePasswordResetNoContactException.class,
        () -> service.createForgotPasswordRequest("{\"username\":\"jdoe\"}"));

    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  void createForgotPasswordRequest_throwsValidationWhenUsernameBlank() {
    when(dataValidator.validateAndExtractUsername("{\"username\":\"\"}"))
        .thenThrow(new PlatformApiDataValidationException(java.util.List.of()));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.createForgotPasswordRequest("{\"username\":\"\"}"));

    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
    verify(appSelfServiceUserRepository, never()).findAppSelfServiceUserByName(any());
  }

  // -------------------------------------------------------------------------
  // renewPassword
  // -------------------------------------------------------------------------

  @Test
  void renewPassword_updatesEncodedPasswordFromExternalToken() {
    RenewPasswordData data =
        new RenewPasswordData("Strong#Abc123", "Strong#Abc123", "external-token");
    when(dataValidator.validateForRenew("{}")).thenReturn(data);

    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);
    // validatePasswordAgainstPolicy is void – do nothing (default)

    SelfServiceRegistration request = mock(SelfServiceRegistration.class);
    when(request.getUsername()).thenReturn("jdoe");
    when(request.isConsumed()).thenReturn(false);
    when(request.isExpired(any())).thenReturn(false);
    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "external-token", SelfServiceRequestType.PASSWORD_RESET))
        .thenReturn(request);

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
    when(appUser.getId()).thenReturn(7L);
    when(appUser.getUsername()).thenReturn("jdoe");
    when(appUser.getFirstname()).thenReturn("John");
    when(appUser.getLastname()).thenReturn("Doe");
    when(appUser.getEmail()).thenReturn("test@test.com");
    when(appUser.getAppUserClientMappings()).thenReturn(new HashSet<>());
    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("jdoe")).thenReturn(appUser);
    when(platformPasswordEncoder.encode(any())).thenReturn("encoded-password");
    when(notificationDeliveryModeUtil.determineMode(any(), any())).thenReturn(true);

    CommandProcessingResult result = service.renewPassword("{}");

    assertNotNull(result);
    assertEquals(7L, result.getResourceId());
    verify(appUser).updatePassword("Strong#Abc123"); // first call with plain text
    verify(appUser).updatePassword("encoded-password"); // second call with encoded
    verify(appUser).updatePasswordResetRequired(false);
    verify(appSelfServiceUserRepository).saveAndFlush(appUser);
    verify(request).markConsumed();
    verify(selfServiceRegistrationRepository).saveAndFlush(request);
    verify(applicationEventPublisher).publishEvent(any(SelfServiceNotificationEvent.class));
  }

  @Test
  void renewPassword_throwsWhenTokenNotFound() {
    RenewPasswordData data =
        new RenewPasswordData("Strong#Abc123", "Strong#Abc123", "invalid-token");
    when(dataValidator.validateForRenew("{}")).thenReturn(data);

    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(null);

    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "invalid-token", SelfServiceRequestType.PASSWORD_RESET))
        .thenReturn(null);

    assertThrows(
        SelfServicePasswordResetTokenException.class, () -> service.renewPassword("{}"));

    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }

  @Test
  void renewPassword_throwsValidationWhenPasswordsDoNotMatch() {
    when(dataValidator.validateForRenew("{}"))
        .thenThrow(new PlatformApiDataValidationException(java.util.List.of()));

    assertThrows(PlatformApiDataValidationException.class, () -> service.renewPassword("{}"));

    verify(selfServiceRegistrationRepository, never())
        .getRequestByExternalAuthorizationToken(any(), any());
    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }

  @Test
  void renewPassword_throwsWhenTokenAlreadyConsumed() {
    RenewPasswordData data =
        new RenewPasswordData("Strong#Abc123", "Strong#Abc123", "external-token");
    when(dataValidator.validateForRenew("{}")).thenReturn(data);

    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(null);

    SelfServiceRegistration request = mock(SelfServiceRegistration.class);
    when(request.isConsumed()).thenReturn(true);
    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "external-token", SelfServiceRequestType.PASSWORD_RESET))
        .thenReturn(request);

    assertThrows(
        SelfServicePasswordResetTokenException.class, () -> service.renewPassword("{}"));

    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }

  @Test
  void renewPassword_throwsWhenTokenExpired() {
    RenewPasswordData data =
        new RenewPasswordData("Strong#Abc123", "Strong#Abc123", "external-token");
    when(dataValidator.validateForRenew("{}")).thenReturn(data);

    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(null);

    SelfServiceRegistration request = mock(SelfServiceRegistration.class);
    when(request.isConsumed()).thenReturn(false);
    when(request.isExpired(any())).thenReturn(true);
    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "external-token", SelfServiceRequestType.PASSWORD_RESET))
        .thenReturn(request);
    when(transactionDateUtil.getCurrentTenantLocalDateTime())
        .thenReturn(LocalDateTime.of(2026, 4, 13, 12, 0, 0));

    assertThrows(
        SelfServicePasswordResetTokenException.class, () -> service.renewPassword("{}"));

    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }
}