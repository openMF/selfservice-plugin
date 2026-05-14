/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.CacheWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.SecurityConfig;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.filters.IdempotencyStoreHelper;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.MDCWrapper;
import org.apache.fineract.infrastructure.jobs.filter.ProgressiveLoanModelCheckerFilter;
import org.apache.fineract.infrastructure.jobs.filter.ProgressiveLoanModelCheckerHelper;
import org.apache.fineract.infrastructure.security.data.PlatformRequestLog;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.infrastructure.security.service.PlatformUserDetailsChecker;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.apache.fineract.notification.service.UserNotificationService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.service.ProgressiveLoanModelProcessingService;
import org.apache.fineract.selfservice.security.domain.PlatformSelfServiceUserRepository;
import org.apache.fineract.selfservice.security.service.TenantAwareJpaPlatformSelfServiceUserDetailsService;
import org.apache.fineract.selfservice.security.starter.SelfServiceSecurityConfiguration;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceRoleReadPlatformService;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@Import({SecurityConfig.class, SelfServiceSecurityConfiguration.class})
@EnableWebSecurity
@EnableWebMvc
@EnableConfigurationProperties(FineractProperties.class)
public class SelfServiceSecurityTestConfig {

  @Bean
  @Primary
  public TenantAwareJpaPlatformUserDetailsService coreUserDetailsService() {
    return mock(TenantAwareJpaPlatformUserDetailsService.class);
  }

  @Bean
  public TenantAwareJpaPlatformSelfServiceUserDetailsService selfServiceUserDetailsService() {
    return mock(TenantAwareJpaPlatformSelfServiceUserDetailsService.class);
  }

  @Bean
  public ServerProperties serverProperties() {
    return mock(ServerProperties.class, RETURNS_DEEP_STUBS);
  }

  @Bean
  @SuppressWarnings("unchecked")
  public ToApiJsonSerializer<PlatformRequestLog> toApiJsonSerializer() {
    return mock(ToApiJsonSerializer.class);
  }

  @Bean
  public ConfigurationDomainService configurationDomainService() {
    return mock(ConfigurationDomainService.class);
  }

  @Bean
  public CacheWritePlatformService cacheWritePlatformService() {
    return mock(CacheWritePlatformService.class);
  }

  @Bean
  public UserNotificationService userNotificationService() {
    return mock(UserNotificationService.class);
  }

  @Bean
  public AuthTenantDetailsService basicAuthTenantDetailsService() {
    return mock(AuthTenantDetailsService.class);
  }

  @Bean
  public BusinessDateReadPlatformService businessDateReadPlatformService() {
    return mock(BusinessDateReadPlatformService.class);
  }

  @Bean
  public MDCWrapper mdcWrapper() {
    return mock(MDCWrapper.class);
  }

  @Bean
  public FineractRequestContextHolder fineractRequestContextHolder() {
    return new FineractRequestContextHolder();
  }

  @Bean
  public IdempotencyStoreHelper idempotencyStoreHelper() {
    return mock(IdempotencyStoreHelper.class);
  }

  @Bean
  public PlatformUserDetailsChecker platformUserDetailsChecker() {
    return mock(PlatformUserDetailsChecker.class);
  }

  @Bean
  public ProgressiveLoanModelCheckerFilter progressiveLoanModelCheckerFilter(
      LoanRepository loanRepository,
      ProgressiveLoanModelProcessingService progressiveLoanModelProcessingService,
      ProgressiveLoanModelCheckerHelper progressiveLoanModelCheckerHelper) {
    return new ProgressiveLoanModelCheckerFilter(
        loanRepository, progressiveLoanModelProcessingService, progressiveLoanModelCheckerHelper);
  }

  @Bean
  public LoanRepository loanRepository() {
    return mock(LoanRepository.class);
  }

  @Bean
  public ProgressiveLoanModelProcessingService progressiveLoanModelProcessingService() {
    return mock(ProgressiveLoanModelProcessingService.class);
  }

  @Bean
  public ProgressiveLoanModelCheckerHelper progressiveLoanModelCheckerHelper() {
    return mock(ProgressiveLoanModelCheckerHelper.class);
  }

  @Bean
  public PlatformUserRepository platformUserRepository() {
    return mock(PlatformUserRepository.class);
  }

  @Bean
  public PlatformSelfServiceUserRepository platformSelfServiceUserRepository() {
    return mock(PlatformSelfServiceUserRepository.class);
  }

  @Bean
  public SelfServiceRoleReadPlatformService selfServiceRoleReadPlatformService() {
    return mock(SelfServiceRoleReadPlatformService.class);
  }
}
