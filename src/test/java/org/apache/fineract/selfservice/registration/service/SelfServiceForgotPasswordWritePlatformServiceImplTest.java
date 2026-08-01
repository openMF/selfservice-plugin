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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
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

  // Only the 9 dependencies declared in the refactored class
  @Mock private SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  @Mock private FromJsonHelper fromApiJsonHelper;

  @Mock
  private SelfServiceRegistrationReadPlatformService selfServiceRegistrationReadPlatformService;

  @Mock private AppSelfServiceUserRepository appSelfServiceUserRepository;
  @Mock private PasswordValidationPolicyRepository passwordValidationPolicyRepository;
  @Mock private PlatformPasswordEncoder platformPasswordEncoder;
  @Mock private SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private Environment env;

  private SelfServiceForgotPasswordWritePlatformServiceImpl service;
  
  @Mock private TransactionDateUtil transactionDateUtil;

  @BeforeEach
  void setUp() {
    service =
        new SelfServiceForgotPasswordWritePlatformServiceImpl(
            selfServiceRegistrationRepository,
            fromApiJsonHelper,
            appSelfServiceUserRepository,
            passwordValidationPolicyRepository,
            platformPasswordEncoder,
            selfServiceAuthorizationTokenService,
            applicationEventPublisher,
            env, 
            transactionDateUtil);
  }

  @Test
  void createForgotPasswordRequest_persistsPasswordResetRequest() {
    // Given: a valid JSON and a user with email and mobile number
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.usernameParamName), any(JsonElement.class)))
        .thenReturn("jdoe");
    when(selfServiceAuthorizationTokenService.generateToken()).thenReturn("123456");
    LocalDateTime expectedExpiry = LocalDateTime.of(2026, 4, 13, 12, 0, 30);
    when(selfServiceAuthorizationTokenService.calculateExpiry(any())).thenReturn(expectedExpiry);

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
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

    // When
    SelfServiceRegistration result = service.createForgotPasswordRequest("{\"username\":\"jdoe\"}");

    // Then
    assertNotNull(result);
    assertEquals(SelfServiceRequestType.PASSWORD_RESET, result.getRequestType());
    assertEquals("123456", result.getExternalAuthorizationToken());
    assertEquals(expectedExpiry, result.getExpiresAt());

    verify(selfServiceRegistrationRepository)
        .saveAndFlush(argThat(request -> expectedExpiry.equals(request.getExpiresAt())));

    // Verify that a notification event was published
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
  void createForgotPasswordRequest_returnsNullWhenUserNotFound() {
    // Given: a username that doesn't exist
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.usernameParamName), any(JsonElement.class)))
        .thenReturn("nonexistent");
    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("nonexistent")).thenReturn(null);

    // When
    SelfServiceRegistration result =
        service.createForgotPasswordRequest("{\"username\":\"nonexistent\"}");

    // Then
    assertNull(result);
    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  void createForgotPasswordRequest_returnsNullWhenUserHasNoEmailOrMobile() {
    // Given: a user with no contact information
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.usernameParamName), any(JsonElement.class)))
        .thenReturn("jdoe");

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
    when(appUser.getEmail()).thenReturn(null);
    Set<AppSelfServiceUserClientMapping> emptyMappings = new HashSet<>();
    when(appUser.getAppUserClientMappings()).thenReturn(emptyMappings);

    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("jdoe")).thenReturn(appUser);

    // When
    SelfServiceRegistration result = service.createForgotPasswordRequest("{\"username\":\"jdoe\"}");

    // Then
    assertNull(result);
    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  void createForgotPasswordRequest_throwsWhenUsernameIsBlank() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.usernameParamName), any(JsonElement.class)))
        .thenReturn("");

    assertThrows(
        IllegalArgumentException.class,
        () -> service.createForgotPasswordRequest("{\"username\":\"\"}"));

    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
  }

  @Test
  void renewPassword_updatesEncodedPasswordFromExternalToken() {
    // Given: a valid reset token and matching passwords
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.passwordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.repeatPasswordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName),
            any(JsonElement.class)))
        .thenReturn("external-token");

    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(policy.getRegex()).thenReturn(".*");
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);

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
    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("jdoe")).thenReturn(appUser);
    when(platformPasswordEncoder.encode(any())).thenReturn("encoded-password");

    // When
    CommandProcessingResult result = service.renewPassword("{}");

    // Then
    assertNotNull(result);
    assertEquals(7L, result.getResourceId());
    verify(appUser).updatePassword("encoded-password");
    verify(appUser).updatePasswordResetRequired(false);
    verify(appSelfServiceUserRepository).saveAndFlush(appUser);
    verify(request).markConsumed();
    verify(selfServiceRegistrationRepository).saveAndFlush(request);

    // Verify success notification
    verify(applicationEventPublisher).publishEvent(any(SelfServiceNotificationEvent.class));
  }

  @Test
  void renewPassword_throwsWhenTokenNotFound() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.passwordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.repeatPasswordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName),
            any(JsonElement.class)))
        .thenReturn("invalid-token");

    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);

    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "invalid-token", SelfServiceRequestType.PASSWORD_RESET))
        .thenReturn(null);

    assertThrows(IllegalArgumentException.class, () -> service.renewPassword("{}"));

    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }

  @Test
  void renewPassword_throwsWhenPasswordsDoNotMatch() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.passwordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.repeatPasswordParamName), any(JsonElement.class)))
        .thenReturn("Different#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName),
            any(JsonElement.class)))
        .thenReturn("external-token");

    assertThrows(IllegalArgumentException.class, () -> service.renewPassword("{}"));

    verify(selfServiceRegistrationRepository, never())
        .getRequestByExternalAuthorizationToken(any(), any());
    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }

  @Test
  void renewPassword_throwsWhenTokenAlreadyConsumed() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.passwordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.repeatPasswordParamName), any(JsonElement.class)))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName),
            any(JsonElement.class)))
        .thenReturn("external-token");

    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);

    SelfServiceRegistration request = mock(SelfServiceRegistration.class);
    when(request.isConsumed()).thenReturn(true);
    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "external-token", SelfServiceRequestType.PASSWORD_RESET))
        .thenReturn(request);

    assertThrows(IllegalArgumentException.class, () -> service.renewPassword("{}"));

    verify(appSelfServiceUserRepository, never()).saveAndFlush(any());
  }
}
