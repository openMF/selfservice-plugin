/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import static org.apache.fineract.infrastructure.core.service.migration.TenantDatabaseUpgradeService.CUSTOM_CHANGELOG_CONTEXT;
import static org.apache.fineract.infrastructure.core.service.migration.TenantDatabaseUpgradeService.INITIAL_SWITCH_CONTEXT;
import static org.apache.fineract.infrastructure.core.service.migration.TenantDatabaseUpgradeService.TENANT_DB_CONTEXT;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Arrays;
import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.migration.ExtendedSpringLiquibaseFactory;
import org.apache.fineract.infrastructure.core.service.migration.TenantDataSourceFactory;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.tenant.exception.TenantSchemaMigrationFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Populates a newly created tenant's schema with the platform's tables, on demand.
 *
 * <p><strong>Why this exists.</strong> Fineract migrates tenants in {@code
 * TenantDatabaseUpgradeService}, which is an {@code InitializingBean} - it runs once, at startup,
 * over the tenants that existed then - and every plugin that owns tables does the same through its
 * own startup bean ({@code SelfServiceLiquibaseConfig}, the savings plugin's {@code
 * SavingsLiquibaseConfig}). A tenant created through this API would therefore sit with an empty or
 * partial schema until the next restart, which does not meet MX-406's "creating a tenant provisions
 * the corresponding schema".
 *
 * <p>The goal is a new tenant whose schema is indistinguishable from one migrated at startup. So
 * this reproduces the startup sequence rather than inventing one: core's changelog through core's
 * own {@link ExtendedSpringLiquibaseFactory}, then each plugin changelog exactly as its startup
 * bean applies it.
 */
@Service
@Slf4j
public class TenantSchemaMigrationService {

  /**
   * The plugin changelogs startup applies, in the order it applies them.
   *
   * <p><strong>A list, not classpath discovery.</strong> Scanning for every {@code
   * module-changelog-master.xml} was ruled out by a running installation: {@code fineract-branch}
   * ships one that neither core's master includes nor any startup bean runs, so discovery would
   * give API-created tenants tables that startup-migrated tenants lack.
   *
   * <p><strong>Order mirrors startup.</strong> Self-service runs before savings because that is the
   * order the startup beans were observed to run in, so a new tenant goes through the same sequence
   * as every other tenant. It is not forced by the one table the two share: both changelogs create
   * {@code m_selfservice_office_service} behind a {@code not tableExists} precondition, so either
   * order is safe for that table. Keeping the startup order guards against cross-plugin
   * dependencies that are not guarded that way.
   *
   * <p><strong>Strings must match the startup beans exactly.</strong> Liquibase identifies a
   * changeset partly by the path it was loaded from. Using the same {@code classpath:/...} strings
   * as the startup beans means the next startup finds every changeset already applied, instead of
   * re-applying them under a different identity and failing on tables that already exist.
   */
  static final String DEFAULT_PLUGIN_CHANGELOGS =
      "classpath:/db/changelog/tenant/module/selfservice/module-changelog-master.xml,"
          + "classpath:/db/changelog/tenant/module/savings/module-changelog-master.xml";

  private final TenantDetailsService tenantDetailsService;
  private final TenantDataSourceFactory tenantDataSourceFactory;
  private final ExtendedSpringLiquibaseFactory liquibaseFactory;
  private final ResourceLoader resourceLoader;
  private final List<String> pluginChangelogs;

  public TenantSchemaMigrationService(
      final TenantDetailsService tenantDetailsService,
      final TenantDataSourceFactory tenantDataSourceFactory,
      final ExtendedSpringLiquibaseFactory liquibaseFactory,
      final ResourceLoader resourceLoader,
      @Value("${fineract.tenant-management.plugin-changelogs:" + DEFAULT_PLUGIN_CHANGELOGS + "}")
          final String pluginChangelogs) {
    this.tenantDetailsService = tenantDetailsService;
    this.tenantDataSourceFactory = tenantDataSourceFactory;
    this.liquibaseFactory = liquibaseFactory;
    this.resourceLoader = resourceLoader;
    this.pluginChangelogs =
        Arrays.stream(pluginChangelogs.split(","))
            .map(String::trim)
            .filter(changelog -> !changelog.isEmpty())
            .toList();
  }

  /**
   * Brings a tenant's schema up to the current version.
   *
   * <p>The tenant is re-read from the registry rather than passed in, so the connection details and
   * the encrypted credentials come back through core's own mapping - including the master password
   * hash that {@link TenantDataSourceFactory} checks before it will open a datasource.
   *
   * @param identifier identifier of a tenant already present in the registry
   * @throws TenantSchemaMigrationFailedException if the schema could not be migrated
   */
  public void migrate(final String identifier) {
    log.info("Migrating schema for tenant {}", identifier);

    // Captured so the caller's context can be put back afterwards. This runs inside the
    // administrator's own request, in the master context; the
    // migration has to point the thread at the new tenant, and simply clearing it
    // afterwards left the rest of that request with no tenant at all - which is how audit
    // rows for CREATE came to lose the acting user and tenant.
    final FineractPlatformTenant callerTenant = ThreadLocalContextUtil.getTenant();
    final FineractContext callerContext = captureCallerContext();

    try {
      final FineractPlatformTenant tenant = tenantDetailsService.loadTenantById(identifier);

      // Changesets read the current tenant from the thread context, exactly as they
      // do during the startup migration.
      ThreadLocalContextUtil.setTenant(tenant);

      try (HikariDataSource tenantDataSource = tenantDataSourceFactory.create(tenant)) {
        applyCoreChangelog(tenantDataSource, identifier);
        applyPluginChangelogs(tenantDataSource, identifier);
      }

      log.info("Schema for tenant {} is up to date", identifier);
    } catch (final Exception e) {
      throw new TenantSchemaMigrationFailedException(identifier, e);
    } finally {
      restoreCallerContext(callerContext, callerTenant);
    }
  }

  /**
   * Takes a snapshot of the whole thread context, not only the tenant, so business dates and the
   * action context the request was using survive the migration too.
   *
   * @return the snapshot, or null if the platform refuses to build one on this thread
   */
  private static FineractContext captureCallerContext() {
    try {
      return ThreadLocalContextUtil.getContext();
    } catch (final RuntimeException e) {
      return null;
    }
  }

  /**
   * Puts the caller's context back. Falls back to the tenant alone when no full snapshot could be
   * taken, so the administrator's request never continues pointed at the tenant just migrated.
   */
  private static void restoreCallerContext(
      final FineractContext callerContext, final FineractPlatformTenant callerTenant) {
    if (callerContext != null) {
      ThreadLocalContextUtil.init(callerContext);
    } else if (callerTenant != null) {
      ThreadLocalContextUtil.setTenant(callerTenant);
    } else {
      ThreadLocalContextUtil.clearTenant();
    }
  }

  /**
   * Applies Fineract's own tenant changelog in the two passes core performs.
   *
   * <p>The first pass includes the {@code initial_switch} context, which carries the baseline
   * schema a brand-new database needs; the second runs without it, which is how core applies
   * everything layered on top. Core's third path - {@code changeLogSync} for a database still
   * carrying Flyway metadata - is deliberately absent: that exists to adopt a pre-1.6 installation,
   * and a schema this service creates is always empty.
   *
   * <p>The tenant identifier is passed as a context because core does the same, to keep Liquibase
   * from caching one tenant's migration and reusing it for another.
   */
  private void applyCoreChangelog(final HikariDataSource dataSource, final String identifier)
      throws Exception {
    final SpringLiquibase baseline =
        liquibaseFactory.create(
            dataSource,
            TENANT_DB_CONTEXT,
            CUSTOM_CHANGELOG_CONTEXT,
            INITIAL_SWITCH_CONTEXT,
            identifier);
    baseline.afterPropertiesSet();

    final SpringLiquibase remainder =
        liquibaseFactory.create(
            dataSource, TENANT_DB_CONTEXT, CUSTOM_CHANGELOG_CONTEXT, identifier);
    remainder.afterPropertiesSet();
  }

  /**
   * Applies each plugin changelog the way its startup bean does.
   *
   * <p>A changelog that is not on the classpath is skipped, not treated as an error: an
   * installation without the savings plugin is a supported deployment, and must still be able to
   * create tenants. The configured list therefore describes what to apply when present.
   */
  private void applyPluginChangelogs(final HikariDataSource dataSource, final String identifier)
      throws Exception {
    for (final String changelog : pluginChangelogs) {
      if (!resourceLoader.getResource(changelog).exists()) {
        log.info(
            "Plugin changelog {} is not installed; skipping it for tenant {}",
            changelog,
            identifier);
        continue;
      }
      // Configured exactly like the startup beans - data source, changelog, shouldRun and
      // nothing else - so the recorded changeset identities match theirs.
      final SpringLiquibase pluginLiquibase = new SpringLiquibase();
      pluginLiquibase.setDataSource(dataSource);
      pluginLiquibase.setChangeLog(changelog);
      pluginLiquibase.setShouldRun(true);
      pluginLiquibase.afterPropertiesSet();
      log.info("Applied plugin changelog {} to tenant {}", changelog, identifier);
    }
  }
}
