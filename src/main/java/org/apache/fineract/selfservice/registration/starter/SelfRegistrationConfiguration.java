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
package org.apache.fineract.selfservice.registration.starter;

import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.service.SelfServiceAuthorizationTokenService;
import org.apache.fineract.selfservice.registration.service.SelfServiceForgotPassworWritePlatformService;
import org.apache.fineract.selfservice.registration.service.SelfServiceForgotPasswordWritePlatformServiceImpl;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationReadPlatformService;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationReadPlatformServiceImpl;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationWritePlatformService;
import org.apache.fineract.selfservice.registration.service.SelfServiceRegistrationWritePlatformServiceImpl;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.SelfServiceUserDomainService;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import jakarta.annotation.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

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
  public MessageSource registrationMessageSource() {
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("i18n/messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    return messageSource;
  }

  /**
   * Provides the Thymeleaf engine for rendering registration email templates.
   *
   * @return configured template engine
   */
  @Bean
  public SpringTemplateEngine registrationTemplateEngine() {
    ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
    templateResolver.setPrefix("mail-templates/");
    templateResolver.setSuffix(".html");
    templateResolver.setTemplateMode(TemplateMode.HTML);
    templateResolver.setCharacterEncoding("UTF-8");

    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);
    templateEngine.setTemplateEngineMessageSource(registrationMessageSource());
    return templateEngine;
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
      SelfServicePluginEmailService selfServicePluginEmailService,
      SmsMessageRepository smsMessageRepository,
      SmsMessageScheduledJobService smsMessageScheduledJobService,
      SmsCampaignDropdownReadPlatformService smsCampaignDropdownReadPlatformService,
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
      ApplicationEventPublisher applicationEventPublisher,
      @Nullable ExternalNotificationSystemClient externalNotificationSystemClient) {
    return new SelfServiceRegistrationWritePlatformServiceImpl(
        selfServiceRegistrationRepository,
        fromApiJsonHelper,
        selfServiceRegistrationReadPlatformService,
        clientRepository,
        passwordValidationPolicy,
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
        registrationTemplateEngine(),
        registrationMessageSource(),
        applicationEventPublisher,
        externalNotificationSystemClient);
  }

  @Bean
  @ConditionalOnMissingBean(SelfServiceForgotPassworWritePlatformService.class)
  public SelfServiceForgotPassworWritePlatformService selfServiceForgotPassworWritePlatformService(
      SelfServiceRegistrationRepository selfServiceRegistrationRepository,
      FromJsonHelper fromApiJsonHelper,
      SelfServiceRegistrationReadPlatformService selfServiceRegistrationReadPlatformService,
      ClientRepositoryWrapper clientRepository,
      PasswordValidationPolicyRepository passwordValidationPolicy,
      SelfServiceUserDomainService userDomainService,
      SelfServicePluginEmailService selfServicePluginEmailService,
      SmsMessageRepository smsMessageRepository,
      SmsMessageScheduledJobService smsMessageScheduledJobService,
      SmsCampaignDropdownReadPlatformService smsCampaignDropdownReadPlatformService,
      AppSelfServiceUserReadPlatformService appUserReadPlatformService,
      RoleRepository roleRepository,
      AppSelfServiceUserClientMappingRepository appUserClientMappingRepository,
      JdbcTemplate jdbcTemplate,
      AppUserRepository appUserRepository,
      Environment env,
      PlatformPasswordEncoder platformPasswordEncoder,
      AppSelfServiceUserRepository appSelfServiceUserRepository,
      SelfServiceAuthorizationTokenService selfServiceAuthorizationTokenService) {
    return new SelfServiceForgotPasswordWritePlatformServiceImpl(
        selfServiceRegistrationRepository,
        fromApiJsonHelper,
        selfServiceRegistrationReadPlatformService,
        clientRepository,
        passwordValidationPolicy,
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
        registrationTemplateEngine(),
        registrationMessageSource());
  }
}
