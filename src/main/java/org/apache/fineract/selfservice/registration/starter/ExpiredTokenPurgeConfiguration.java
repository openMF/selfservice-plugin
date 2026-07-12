/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.starter;

import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.service.ExpiredTokenPurgeScheduler;
import org.apache.fineract.selfservice.registration.service.ExpiredTokenPurgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration for the expired token purge subsystem.
 *
 * <p>Activates {@link EnableScheduling} so that {@link ExpiredTokenPurgeScheduler}'s
 * {@code @Scheduled} method is picked up by the Spring task scheduler.
 *
 * <p><strong>DataSource routing contract:</strong> The {@link JdbcTemplate} bean injected into
 * {@link ExpiredTokenPurgeService} must be backed by Fineract's {@code AbstractRoutingDataSource}
 * (the routing datasource) so that {@code ThreadLocalContextUtil.setTenant()} routes SQL to the
 * correct tenant schema. In Fineract's standard auto-configuration, the primary {@code DataSource}
 * bean is already the routing datasource, so {@code @Autowired JdbcTemplate} resolves correctly.
 */
@Configuration
@EnableScheduling
public class ExpiredTokenPurgeConfiguration {

  @Bean
  @ConditionalOnMissingBean(ExpiredTokenPurgeService.class)
  public ExpiredTokenPurgeService expiredTokenPurgeService(
      SelfServiceRegistrationRepository repository, JdbcTemplate jdbcTemplate) {
    return new ExpiredTokenPurgeService(repository, jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(ExpiredTokenPurgeScheduler.class)
  @ConditionalOnProperty(
      name = "mifos.self.service.token.purge.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ExpiredTokenPurgeScheduler expiredTokenPurgeScheduler(
      TenantDetailsService tenantDetailsService,
      ExpiredTokenPurgeService purgeService,
      Environment env,
      FineractProperties fineractProperties) {
    return new ExpiredTokenPurgeScheduler(
        tenantDetailsService, purgeService, env, fineractProperties);
  }
}
