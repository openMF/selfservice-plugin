/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.tenant.filter.TenantStatusEnforcementFilter;
import org.apache.fineract.tenant.service.TenantStatusLookupService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for tenant administration against the central tenant store.
 *
 * <p>Everything here is bound to the {@code hikariTenantDataSource} bean - the registry database -
 * rather than the per-tenant {@code routingDataSource} the rest of the plugin uses.
 */
@Configuration
@Slf4j
public class TenantManagementConfig {

  /**
   * Applies this plugin's migrations to the tenant store database.
   *
   * <p>Fineract core's tenant-store changelog is a flat, hard-coded list of parts with no module
   * extension point - unlike the per-tenant master, which has one. A plugin therefore cannot append
   * to it, so the plugin runs its own changelog here instead. This keeps the whole feature inside
   * the plugin: no fork of Apache Fineract is required to add the {@code status} column MX-406
   * needs.
   *
   * <p>Runs after core's own upgrade so the {@code tenants} table it alters is guaranteed to exist;
   * on a fresh installation core creates the registry and this then extends it. The changesets are
   * additive and individually guarded by preconditions, so a repeat run is a no-op.
   */
  @Bean
  @DependsOn("tenantDatabaseUpgradeService")
  public String runTenantManagementTenantStoreMigrations(
      @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {

    log.info("Applying tenant administration migrations to the tenant store");

    // Built and run locally rather than returned as a SpringLiquibase bean, matching
    // SelfServiceLiquibaseConfig. Publishing a SpringLiquibase bean would enter the
    // pool that Spring Boot's Liquibase autoconfiguration and the platform's own
    // migration wiring select from, and this changelog must apply to the tenant store
    // and nothing else.
    final SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(tenantStoreDataSource);
    liquibase.setChangeLog(
        "classpath:/db/changelog/tenantstore/module/tenantmanagement/module-changelog-master.xml");
    liquibase.setShouldRun(true);

    try {
      liquibase.afterPropertiesSet();
    } catch (final Exception e) {
      // Fail fast and loudly: if the status column is missing, every tenant
      // administration query would fail later with an obscure SQL error instead.
      throw new IllegalStateException("Tenant administration migrations failed", e);
    }

    log.info("Tenant administration migrations completed");
    return "Tenant administration migrations completed";
  }

  /**
   * The status filter, as a plain bean so it is not auto-registered a second time.
   *
   * <p>A filter annotated {@code @Component} is picked up by Spring Boot's servlet
   * auto-registration as well as by the registration below, which would run it twice per request.
   */
  @Bean
  public TenantStatusEnforcementFilter tenantStatusEnforcementFilter(
      final TenantStatusLookupService statusLookupService) {
    return new TenantStatusEnforcementFilter(statusLookupService);
  }

  /**
   * Puts the status filter in front of everything else.
   *
   * <p>Ordered ahead of Spring Security's chain so a suspended tenant is turned away before any
   * credential is read, and mapped to every path because a suspension has to hold for the whole
   * platform, not only for the endpoints this plugin adds.
   */
  @Bean
  public FilterRegistrationBean<TenantStatusEnforcementFilter>
      tenantStatusEnforcementFilterRegistration(final TenantStatusEnforcementFilter filter) {
    final FilterRegistrationBean<TenantStatusEnforcementFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  /**
   * Transaction manager for the registry.
   *
   * <p>Not marked {@code @Primary}: the platform's own transaction manager must keep serving every
   * other component. This one is injected by name, and only by tenant administration.
   */
  @Bean
  public PlatformTransactionManager tenantStoreTransactionManager(
      @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {
    return new DataSourceTransactionManager(tenantStoreDataSource);
  }

  /**
   * Wraps registry writes in a single transaction.
   *
   * <p>Creating a tenant touches two tables and must be all-or-nothing: a connection row with no
   * tenant row would be an orphan no API surfaces, and a tenant row is impossible without one
   * because {@code oltp_id} is NOT NULL.
   */
  @Bean
  public TransactionTemplate tenantStoreTransactionTemplate(
      @Qualifier("tenantStoreTransactionManager") final PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }
}
