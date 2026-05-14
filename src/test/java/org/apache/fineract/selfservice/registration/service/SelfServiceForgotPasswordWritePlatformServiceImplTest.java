package org.apache.fineract.selfservice.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.SelfServiceUserDomainService;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.ITemplateEngine;

@ExtendWith(MockitoExtension.class)
class SelfServiceForgotPasswordWritePlatformServiceImplTest {

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
  @Mock private Environment env;
  @Mock private PlatformPasswordEncoder platformPasswordEncoder;
  @Mock private AppSelfServiceUserRepository appSelfServiceUserRepository;
  @Mock private SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService;
  @Mock private ITemplateEngine registrationTemplateEngine;
  @Mock private MessageSource registrationMessageSource;

  private SelfServiceForgotPasswordWritePlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new SelfServiceForgotPasswordWritePlatformServiceImpl(
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
            env,
            platformPasswordEncoder,
            appSelfServiceUserRepository,
            selfServiceAuthorizationTokenService,
            registrationTemplateEngine,
            registrationMessageSource);
  }

  @Test
  void createForgotPasswordRequest_persistsPasswordResetRequest() {
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.usernameParamName), any()))
        .thenReturn("jdoe");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalIdParamName), any()))
        .thenReturn(null);
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalIDParamName), any()))
        .thenReturn(null);
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.authenticationModeParamName), any()))
        .thenReturn("email");
    when(appUserReadPlatformService.isUsernameExist("jdoe")).thenReturn(true);
    when(selfServiceAuthorizationTokenService.generateToken()).thenReturn("123456");
    LocalDateTime expectedExpiry = LocalDateTime.of(2026, 4, 13, 12, 0, 30);
    when(selfServiceAuthorizationTokenService.calculateExpiry(any())).thenReturn(expectedExpiry);

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
    when(appUser.getEmail()).thenReturn("test@test.com");
    Client client = mock(Client.class);
    when(client.getAccountNumber()).thenReturn("0001");
    when(client.getFirstname()).thenReturn("John");
    when(client.getMiddlename()).thenReturn(null);
    when(client.getLastname()).thenReturn("Doe");
    when(client.getMobileNo()).thenReturn("5551234567");
    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getAppUser()).thenReturn(appUser);
    when(mapping.getClient()).thenReturn(client);
    when(appUserClientMappingRepository.fetchByAppuserUsername("jdoe")).thenReturn(mapping);

    SelfServiceRegistration result = service.createForgotPasswordRequest("{}");

    assertNotNull(result);
    assertEquals(SelfServiceRequestType.PASSWORD_RESET, result.getRequestType());
    assertEquals("123456", result.getExternalAuthorizationToken());
    assertEquals(expectedExpiry, result.getExpiresAt());
    verify(selfServiceRegistrationRepository)
        .saveAndFlush(argThat(request -> expectedExpiry.equals(request.getExpiresAt())));
  }

  @Test
  void createForgotPasswordRequest_returnsNoRequestWhenExternalIdDoesNotMatch() {
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.usernameParamName), any()))
        .thenReturn("jdoe");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalIdParamName), any()))
        .thenReturn("wrong-id");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.authenticationModeParamName), any()))
        .thenReturn("email");
    when(appUserReadPlatformService.isUsernameExist("jdoe")).thenReturn(true);

    AppSelfServiceUser appUser = mock(AppSelfServiceUser.class);
    Client client = mock(Client.class);
    when(client.getExternalId())
        .thenReturn(new org.apache.fineract.infrastructure.core.domain.ExternalId("expected-id"));
    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getAppUser()).thenReturn(appUser);
    when(mapping.getClient()).thenReturn(client);
    when(appUserClientMappingRepository.fetchByAppuserUsername("jdoe")).thenReturn(mapping);

    assertNull(service.createForgotPasswordRequest("{}"));
    verify(selfServiceRegistrationRepository, never())
        .saveAndFlush(any(SelfServiceRegistration.class));
  }

  @Test
  void renewPassword_updatesEncodedPasswordFromExternalToken() {
    when(fromApiJsonHelper.extractStringNamed(eq(SelfServiceApiConstants.passwordParamName), any()))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.repeatPasswordParamName), any()))
        .thenReturn("Strong#Abc123");
    when(fromApiJsonHelper.extractStringNamed(
            eq(SelfServiceApiConstants.externalAuthenticationTokenParamName), any()))
        .thenReturn("external-token");
    PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(policy.getRegex()).thenReturn(".*");
    when(policy.getDescription()).thenReturn("any");
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
    when(appSelfServiceUserRepository.findAppSelfServiceUserByName("jdoe")).thenReturn(appUser);
    when(platformPasswordEncoder.encode(any())).thenReturn("encoded-password");

    CommandProcessingResult result = service.renewPassword("{}");

    assertNotNull(result);
    assertEquals(7L, result.getResourceId());
    verify(appUser).updatePassword("encoded-password");
    verify(appUser).updatePasswordResetRequired(false);
    verify(appSelfServiceUserRepository).saveAndFlush(appUser);
    verify(request).markConsumed();
    verify(selfServiceRegistrationRepository).saveAndFlush(request);
  }
}
