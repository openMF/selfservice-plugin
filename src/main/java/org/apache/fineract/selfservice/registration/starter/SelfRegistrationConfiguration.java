/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.starter;

import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.TransactionDateManagementService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.service.ClientIdentifierWritePlatformService;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.registration.data.SelfServiceForgotPasswordDataValidator;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.service.SelfServiceAuthorizationTokenService;
import org.apache.fineract.selfservice.registration.service.SelfServiceForgotPasswordWritePlatformService;
import org.apache.fineract.selfservice.registration.service.SelfServiceForgotPasswordWritePlatformServiceImpl;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationReadPlatformService;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationReadPlatformServiceImpl;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationWritePlatformService;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationWritePlatformServiceImpl;
import org.apache.fineract.selfservice.security.service.SelfServiceDeviceFingerprintService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.SelfServiceUserDomainService;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class SelfRegistrationConfiguration {

  @Bean
  @ConditionalOnMissingBean(SelfServiceRegistrationReadPlatformService.class)
  public SelfServiceRegistrationReadPlatformService selfServiceRegistrationReadPlatformService(
      JdbcTemplate jdbcTemplate) {
    return new SelfServiceRegistrationReadPlatformServiceImpl(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(SelfServiceAuthorizationTokenService.class)
  public SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService(
      Environment env) {
    return new SelfServiceAuthorizationTokenService(env);
  }

  @Bean
  @ConditionalOnMissingBean(SelfServiceRegistrationWritePlatformService.class)
  public SelfServiceRegistrationWritePlatformService selfServiceRegistrationWritePlatformService(
      SelfServiceRegistrationRepository selfServiceRegistrationRepository,
      FromJsonHelper fromApiJsonHelper,
      SelfServiceRegistrationReadPlatformService selfServiceRegistrationReadPlatformService,
      ClientRepositoryWrapper clientRepository,
      PasswordValidationPolicyRepository passwordValidationPolicy,
      SelfServiceUserDomainService userDomainService,
      AppSelfServiceUserReadPlatformService appUserReadPlatformService,
      RoleRepository roleRepository,
      AppSelfServiceUserClientMappingRepository appUserClientMappingRepository,
      JdbcTemplate jdbcTemplate,
      AppUserRepository appUserRepository,
      ClientWritePlatformService clientWritePlatformService,
      Environment env,
      PlatformPasswordEncoder platformPasswordEncoder,
      AppSelfServiceUserRepository appSelfServiceUserRepository,
      SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService,
      ApplicationEventPublisher applicationEventPublisher,TransactionDateUtil transactionDateUtil,
      TransactionDateManagementService transactionDateManagementService,
      ClientIdentifierWritePlatformService clientIdentifierWritePlatformService,
      SelfServiceDeviceFingerprintService deviceFingerprintService,
      SelfServiceOnboardingStepService onboardingStepService) {

    return new SelfServiceRegistrationWritePlatformServiceImpl(
        selfServiceRegistrationRepository,
        fromApiJsonHelper,
        selfServiceRegistrationReadPlatformService,
        clientRepository,
        passwordValidationPolicy,
        userDomainService,
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
        applicationEventPublisher,
        transactionDateUtil,
        transactionDateManagementService,
        clientIdentifierWritePlatformService,
        deviceFingerprintService,
        onboardingStepService);
  }

  @Bean
  @ConditionalOnMissingBean(SelfServiceForgotPasswordWritePlatformService.class)
  public SelfServiceForgotPasswordWritePlatformService selfServiceForgotPassworWritePlatformService(
      SelfServiceForgotPasswordDataValidator dataValidator,
      AppSelfServiceUserRepository appSelfServiceUserRepository,
      SelfServiceRegistrationRepository selfServiceRegistrationRepository,
      PasswordValidationPolicyRepository passwordValidationPolicyRepository,
      PlatformPasswordEncoder platformPasswordEncoder,
      SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService,
      ApplicationEventPublisher applicationEventPublisher,
      Environment env,
      TransactionDateUtil transactionDateUtil,
      NotificationDeliveryModeUtil notificationDeliveryModeUtil,
     NotificationCooldownCache notificationCooldownCache) {

    return new SelfServiceForgotPasswordWritePlatformServiceImpl(
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
  }
}
