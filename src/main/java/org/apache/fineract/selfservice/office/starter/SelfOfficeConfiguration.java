/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.starter;

import org.apache.fineract.selfservice.office.service.SelfServiceOfficeReadPlatformService;
import org.apache.fineract.selfservice.office.service.SelfServiceOfficeReadPlatformServiceImpl;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring configuration for self-service office read operations.
 *
 * <p>Registers a default {@link SelfServiceOfficeReadPlatformService} bean backed by JDBC unless
 * one is already provided by the application context.
 */
@Configuration
public class SelfOfficeConfiguration {

  /**
   * Creates the default {@link SelfServiceOfficeReadPlatformService} implementation.
   *
   * @param jdbcTemplate the JDBC template for database access
   * @param context the self-service security context
   * @return a new service instance
   */
  @Bean
  @ConditionalOnMissingBean(SelfServiceOfficeReadPlatformService.class)
  public SelfServiceOfficeReadPlatformService selfServiceOfficeReadPlatformService(
      JdbcTemplate jdbcTemplate, PlatformSelfServiceSecurityContext context) {
    return new SelfServiceOfficeReadPlatformServiceImpl(jdbcTemplate, context);
  }
}
