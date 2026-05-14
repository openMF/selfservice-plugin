/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.registration.exception.SelfServiceRegistrationNotFoundException;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.SelfServiceUserDomainService;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.domain.Role;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.ITemplateEngine;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfServiceRegistrationWritePlatformServiceImplTest {

  @Mock private SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  @Mock private FromJsonHelper fromApiJsonHelper;

  @Mock
  private SelfServiceRegistrationReadPlatformService selfServiceRegistrationReadPlatformService;

  @Mock private ClientRepositoryWrapper clientRepository;
  @Mock private PasswordValidationPolicyRepository passwordValidationPolicyRepository;
  @Mock private SelfServiceUserDomainService userDomainService;
  @Mock private SelfServicePluginEmailService selfServicePluginEmailService;
  @Mock private SmsMessageRepository smsMessageRepository;
  @Mock private SmsMessageScheduledJobService smsMessageScheduledJobService;
  @Mock private SmsCampaignDropdownReadPlatformService smsCampaignDropdownReadPlatformService;
  @Mock private AppSelfServiceUserReadPlatformService appUserReadPlatformService;
  @Mock private RoleRepository roleRepository;
  @Mock private AppSelfServiceUserClientMappingRepository appUserClientMappingRepository;
  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private AppUserRepository appUserRepository;
  @Mock private ClientWritePlatformService clientWritePlatformService;
  @Mock private Environment env;
  @Mock private PlatformPasswordEncoder platformPasswordEncoder;
  @Mock private AppSelfServiceUserRepository appSelfServiceUserRepository;
  @Mock private SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  @Mock private ITemplateEngine registrationTemplateEngine;
  @Mock private MessageSource registrationMessageSource;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private ExternalNotificationSystemClient externalNotificationSystemClient;

  private SelfServiceRegistrationWritePlatformServiceImpl service;

  @BeforeEach
  void setUp() {

    service =
        new SelfServiceRegistrationWritePlatformServiceImpl(
            selfServiceRegistrationRepository,
            fromApiJsonHelper,
            selfServiceRegistrationReadPlatformService,
            clientRepository,
            passwordValidationPolicyRepository,
            userDomainService,
            selfServicePluginEmailService,
            smsMessageRepository,
            smsMessageScheduledJobService,
            smsCampaignDropdownReadPlatformService,
            appUserReadPlatformService,
            roleRepository,
            appUserClientMappingRepository,
            jdbcTemplate,
            appUserRepository,
            clientWritePlatformService,
            env,
            platformPasswordEncoder,
            appSelfServiceUserRepository,
            selfServiceAuthorizationTokenService,
            registrationTemplateEngine,
            registrationMessageSource,
            applicationEventPublisher,
            externalNotificationSystemClient);

    LocalDate businessDate = LocalDate.of(2026, 1, 2);
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, businessDate);
    businessDates.put(BusinessDateType.COB_DATE, businessDate.minusDays(1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void createRegistrationRequest_throwsOnInvalidPayload() {
    // Mock simple validation flow failing
    org.mockito.Mockito.lenient()
        .when(
            fromApiJsonHelper.extractStringNamed(
                eq(SelfServiceApiConstants.accountNumberParamName), any()))
        .thenReturn(null);
    org.mockito.Mockito.lenient()
        .when(
            fromApiJsonHelper.extractStringNamed(
                eq(SelfServiceApiConstants.authenticationModeParamName), any()))
        .thenReturn("email");
    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);

    assertThrows(
        PlatformApiDataValidationException.class, () -> service.createRegistrationRequest("{}"));
  }

  @Test
  void createRegistrationRequest_throwsClientNotFound() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.accountNumberParamName), any()))
        .thenReturn("12345");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.firstNameParamName), any()))
        .thenReturn("John");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.middleNameParamName), any()))
        .thenReturn(null);
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.lastNameParamName), any()))
        .thenReturn("Doe");
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.usernameParamName), any()))
        .thenReturn("jdoe");
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.passwordParamName), any()))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.authenticationModeParamName), any()))
        .thenReturn("email");
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.emailParamName), any()))
        .thenReturn("john@test.com");

    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(policy.getRegex()).thenReturn(".*");
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);

    when(appUserReadPlatformService.isUsernameExist("jdoe")).thenReturn(false);
    when(selfServiceRegistrationReadPlatformService.isClientExist(
            anyString(), anyString(), any(), anyString(), any(), anyBoolean()))
        .thenReturn(false);

    assertThrows(ClientNotFoundException.class, () -> service.createRegistrationRequest("{}"));
  }

  @Test
  void createRegistrationRequest_persistsRegistration() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.accountNumberParamName), any()))
        .thenReturn("12345");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.firstNameParamName), any()))
        .thenReturn("John");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.middleNameParamName), any()))
        .thenReturn(null);
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.lastNameParamName), any()))
        .thenReturn("Doe");
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.usernameParamName), any()))
        .thenReturn("jdoe");
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.passwordParamName), any()))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.authenticationModeParamName), any()))
        .thenReturn("email");
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.emailParamName), any()))
        .thenReturn("john@test.com");

    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(policy.getRegex()).thenReturn(".*");
    when(passwordValidationPolicyRepository.findActivePasswordValidationPolicy())
        .thenReturn(policy);

    when(appUserReadPlatformService.isUsernameExist("jdoe")).thenReturn(false);
    when(selfServiceRegistrationReadPlatformService.isClientExist(
            anyString(), anyString(), any(), anyString(), any(), anyBoolean()))
        .thenReturn(true);
    Client client = mock(Client.class);
    when(clientRepository.getClientByAccountNumber("12345")).thenReturn(client);
    when(selfServiceAuthorizationTokenService.generateToken()).thenReturn("123456");
    when(selfServiceAuthorizationTokenService.calculateExpiry(any()))
        .thenAnswer(
            invocation -> ((java.time.LocalDateTime) invocation.getArgument(0)).plusSeconds(30));
    when(platformPasswordEncoder.encode(any())).thenReturn("encoded-password");

    SelfServiceRegistration result = service.createRegistrationRequest("{}");

    assertNotNull(result);
    assertEquals("encoded-password", result.getPassword());
  }

  @Test
  void createSelfServiceUser_throwsOnInvalidPayload() {
    when(fromApiJsonHelper.extractLongNamed(eq(SelfServiceApiConstants.requestIdParamName), any()))
        .thenReturn(null);
    assertThrows(
        PlatformApiDataValidationException.class, () -> service.createSelfServiceUser("{}"));
  }

  @Test
  void createSelfServiceUser_throwsNotFound() {
    when(fromApiJsonHelper.extractLongNamed(eq(SelfServiceApiConstants.requestIdParamName), any()))
        .thenReturn(1L);
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName), any()))
        .thenReturn(null);
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.authenticationTokenParamName), any()))
        .thenReturn("123456");

    when(selfServiceRegistrationRepository.getRequestByIdAndAuthenticationToken(
            1L, "123456", SelfServiceRequestType.REGISTRATION))
        .thenReturn(null);

    assertThrows(
        SelfServiceRegistrationNotFoundException.class, () -> service.createSelfServiceUser("{}"));
  }

  @Test
  void createSelfServiceUser_returnsUserWithId() {
    when(fromApiJsonHelper.extractLongNamed(eq(SelfServiceApiConstants.requestIdParamName), any()))
        .thenReturn(1L);
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName), any()))
        .thenReturn(null);
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.authenticationTokenParamName), any()))
        .thenReturn("123456");

    SelfServiceRegistration registration = mock(SelfServiceRegistration.class);
    when(registration.getUsername()).thenReturn("jdoe");
    when(registration.getPassword()).thenReturn("pass");
    when(registration.getEmail()).thenReturn("test@test.com");
    Client client = mock(Client.class);
    when(client.getFirstname()).thenReturn("John");
    when(client.getLastname()).thenReturn("Doe");
    when(registration.getClient()).thenReturn(client);
    Office office = mock(Office.class);
    when(client.getOffice()).thenReturn(office);

    when(selfServiceRegistrationRepository.getRequestByIdAndAuthenticationToken(
            1L, "123456", SelfServiceRequestType.REGISTRATION))
        .thenReturn(registration);

    Role role = mock(Role.class);
    when(roleRepository.getRoleByName(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE))
        .thenReturn(role);

    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);

    try {
      AppSelfServiceUser result = service.createSelfServiceUser("{}");
      assertNotNull(result);
    } finally {
      ThreadLocalContextUtil.setTenant(null);
    }
  }

  @Test
  void createSelfServiceUser_acceptsExternalAuthenticationToken() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName), any()))
        .thenReturn("external-token");

    SelfServiceRegistration registration = mock(SelfServiceRegistration.class);
    when(registration.getUsername()).thenReturn("jdoe");
    when(registration.getPassword()).thenReturn("encoded-pass");
    when(registration.getEmail()).thenReturn("test@test.com");
    Client client = mock(Client.class);
    Office office = mock(Office.class);
    when(client.getOffice()).thenReturn(office);
    when(client.getFirstname()).thenReturn("John");
    when(client.getLastname()).thenReturn("Doe");
    when(registration.getClient()).thenReturn(client);
    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "external-token", SelfServiceRequestType.REGISTRATION))
        .thenReturn(registration);

    Role role = mock(Role.class);
    when(roleRepository.getRoleByName(SelfServiceApiConstants.SELF_SERVICE_USER_ROLE))
        .thenReturn(role);

    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);

    try {
      AppSelfServiceUser result = service.createSelfServiceUser("{}");
      assertNotNull(result);
    } finally {
      ThreadLocalContextUtil.setTenant(null);
    }
  }

  @Test
  void createSelfServiceUser_throwsWhenConsumedOrExpired() {
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName), any()))
        .thenReturn("external-token");
    SelfServiceRegistration registration = mock(SelfServiceRegistration.class);
    when(registration.isExpired(any())).thenReturn(true); // Treat as expired
    when(selfServiceRegistrationRepository.getRequestByExternalAuthorizationToken(
            "external-token", SelfServiceRequestType.REGISTRATION))
        .thenReturn(registration);

    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException.class,
        () -> service.createSelfServiceUser("{}"));
  }
}
