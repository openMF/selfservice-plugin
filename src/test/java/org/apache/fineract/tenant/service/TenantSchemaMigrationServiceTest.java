/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.migration.ExtendedSpringLiquibase;
import org.apache.fineract.infrastructure.core.service.migration.ExtendedSpringLiquibaseFactory;
import org.apache.fineract.infrastructure.core.service.migration.TenantDataSourceFactory;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.tenant.exception.TenantSchemaMigrationFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

class TenantSchemaMigrationServiceTest {

  private TenantDetailsService tenantDetailsService;
  private TenantDataSourceFactory tenantDataSourceFactory;
  private ExtendedSpringLiquibaseFactory liquibaseFactory;
  private ResourceLoader resourceLoader;

  @BeforeEach
  void setUp() throws Exception {
    tenantDetailsService = mock(TenantDetailsService.class);
    tenantDataSourceFactory = mock(TenantDataSourceFactory.class);
    liquibaseFactory = mock(ExtendedSpringLiquibaseFactory.class);
    resourceLoader = mock(ResourceLoader.class);

    when(tenantDetailsService.loadTenantById("beta")).thenReturn(tenant("beta"));
    when(tenantDataSourceFactory.create(any())).thenReturn(mock(HikariDataSource.class));
    when(liquibaseFactory.create(any(), any(String[].class)))
        .thenReturn(mock(ExtendedSpringLiquibase.class));

    // No plugin changelog "installed", so the test never opens a real database.
    final Resource absent = mock(Resource.class);
    when(absent.exists()).thenReturn(false);
    when(resourceLoader.getResource(anyString())).thenReturn(absent);
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  private static FineractPlatformTenant tenant(final String identifier) {
    return FineractPlatformTenant.builder().id(1L).tenantIdentifier(identifier).build();
  }

  private TenantSchemaMigrationService serviceWithChangelogs(final String changelogs) {
    return new TenantSchemaMigrationService(
        tenantDetailsService,
        tenantDataSourceFactory,
        liquibaseFactory,
        resourceLoader,
        changelogs);
  }

  @Test
  void theCallersTenantIsRestoredAfterASuccessfulMigration() {
    // The migration points the thread at the new tenant. Leaving it cleared afterwards
    // made the rest of the administrator's request - including the CREATE audit row -
    // run with no tenant and no acting user.
    ThreadLocalContextUtil.setTenant(tenant("default"));

    serviceWithChangelogs(TenantSchemaMigrationService.DEFAULT_PLUGIN_CHANGELOGS).migrate("beta");

    assertEquals("default", ThreadLocalContextUtil.getTenant().getTenantIdentifier());
  }

  @Test
  void theCallersTenantIsRestoredAfterAFailedMigration() {
    ThreadLocalContextUtil.setTenant(tenant("default"));
    when(tenantDetailsService.loadTenantById("beta"))
        .thenThrow(new IllegalStateException("registry down"));

    assertThrows(
        TenantSchemaMigrationFailedException.class,
        () ->
            serviceWithChangelogs(TenantSchemaMigrationService.DEFAULT_PLUGIN_CHANGELOGS)
                .migrate("beta"));

    assertEquals("default", ThreadLocalContextUtil.getTenant().getTenantIdentifier());
  }

  @Test
  void coreIsMigratedInItsTwoPasses() {
    serviceWithChangelogs(TenantSchemaMigrationService.DEFAULT_PLUGIN_CHANGELOGS).migrate("beta");

    verify(liquibaseFactory, times(2)).create(any(), any(String[].class));
  }

  @Test
  void byDefaultBothStartupPluginChangelogsAreConsideredInStartupOrder() {
    // Self-service then savings, matching the order the startup beans run in.
    serviceWithChangelogs(TenantSchemaMigrationService.DEFAULT_PLUGIN_CHANGELOGS).migrate("beta");

    final var inOrder = org.mockito.Mockito.inOrder(resourceLoader);
    inOrder
        .verify(resourceLoader)
        .getResource(
            "classpath:/db/changelog/tenant/module/selfservice/module-changelog-master.xml");
    inOrder
        .verify(resourceLoader)
        .getResource("classpath:/db/changelog/tenant/module/savings/module-changelog-master.xml");
  }

  @Test
  void aConfiguredListIsTrimmedAndBlankEntriesIgnored() {
    serviceWithChangelogs(" classpath:/a.xml , ,classpath:/b.xml ").migrate("beta");

    verify(resourceLoader).getResource("classpath:/a.xml");
    verify(resourceLoader).getResource("classpath:/b.xml");
    verify(resourceLoader, never()).getResource("");
  }

  @Test
  void aPluginThatIsNotInstalledIsSkippedRatherThanFailingTheCreate() {
    // An installation without the savings plugin must still be able to create tenants.
    serviceWithChangelogs(TenantSchemaMigrationService.DEFAULT_PLUGIN_CHANGELOGS).migrate("beta");

    verify(tenantDataSourceFactory).create(any());
  }
}
