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
package org.apache.fineract.selfservice.registration.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

/**
 * Unit tests for {@link ExpiredTokenPurgeScheduler}.
 *
 * <p>These tests call the real {@link ThreadLocalContextUtil} static methods (set/clear) rather
 * than attempting static mocking, which is consistent with the testing pattern used across this
 * codebase (see {@code SelfServiceRegistrationWritePlatformServiceImplTest}, {@code
 * AppSelfServiceUserTest}, etc.).
 *
 * <p>After each test, the {@code @AfterEach} method resets the ThreadLocal to prevent leaking state
 * into subsequent tests.
 */
@ExtendWith(MockitoExtension.class)
class ExpiredTokenPurgeSchedulerTest {

  @Mock private TenantDetailsService tenantDetailsService;
  @Mock private ExpiredTokenPurgeService purgeService;
  @Mock private Environment env;
  @Mock private FineractProperties fineractProperties;
  @Mock private FineractProperties.FineractModeProperties modeProperties;

  private ExpiredTokenPurgeScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new ExpiredTokenPurgeScheduler(tenantDetailsService, purgeService, env, fineractProperties);
    // Default: batch-enabled instance (the normal case for running scheduled jobs).
    // Lenient because tests that only exercise helper methods (e.g. isTwoFactorPurgeEnabled)
    // never call purgeExpiredTokens() and therefore never consume these stubs.
    lenient().when(fineractProperties.getMode()).thenReturn(modeProperties);
    lenient().when(modeProperties.isBatchWorkerEnabled()).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    // Safety: ensure no leaked tenant context from tests
    try {
      ThreadLocalContextUtil.reset();
    } catch (Exception ignored) {
    }
  }

  // ── Tenant iteration ──────────────────────────────────────────────────────

  @Test
  void purgeExpiredTokens_iteratesAllTenants() {
    FineractPlatformTenant tenant1 = createTenant("tenant1");
    FineractPlatformTenant tenant2 = createTenant("tenant2");
    FineractPlatformTenant tenant3 = createTenant("tenant3");
    when(tenantDetailsService.findAllTenants())
        .thenReturn(Arrays.asList(tenant1, tenant2, tenant3));

    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);

    scheduler.purgeExpiredTokens();

    // Self-service purge invoked once per tenant
    verify(purgeService, times(3)).purgeExpiredSelfServiceTokens();
  }

  @Test
  void purgeExpiredTokens_handlesEmptyTenantList() {
    when(tenantDetailsService.findAllTenants()).thenReturn(Collections.emptyList());

    assertDoesNotThrow(() -> scheduler.purgeExpiredTokens());

    verify(purgeService, never()).purgeExpiredSelfServiceTokens();
    verify(purgeService, never()).purgeExpiredTwoFactorAccessTokens();
  }

  @Test
  void purgeExpiredTokens_handlesSingleTenant() {
    FineractPlatformTenant tenant = createTenant("only-tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));

    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(5);

    scheduler.purgeExpiredTokens();

    verify(purgeService, times(1)).purgeExpiredSelfServiceTokens();
  }

  // ── Error isolation ───────────────────────────────────────────────────────

  @Test
  void purgeExpiredTokens_continuesOnTenantFailure() {
    FineractPlatformTenant tenant1 = createTenant("tenant1");
    FineractPlatformTenant tenant2 = createTenant("tenant2");
    FineractPlatformTenant tenant3 = createTenant("tenant3");
    when(tenantDetailsService.findAllTenants())
        .thenReturn(Arrays.asList(tenant1, tenant2, tenant3));

    // Sequential stubbing: tenant1 succeeds, tenant2 fails, tenant3 succeeds
    when(purgeService.purgeExpiredSelfServiceTokens())
        .thenReturn(1)
        .thenThrow(new RuntimeException("DB connection lost"))
        .thenReturn(3);

    assertDoesNotThrow(() -> scheduler.purgeExpiredTokens());

    // All three tenants attempted despite tenant2 failure
    verify(purgeService, times(3)).purgeExpiredSelfServiceTokens();
  }

  @Test
  void purgeExpiredTokens_continuesWhen2faPurgeFails() {
    FineractPlatformTenant tenant1 = createTenant("tenant1");
    FineractPlatformTenant tenant2 = createTenant("tenant2");
    when(tenantDetailsService.findAllTenants()).thenReturn(Arrays.asList(tenant1, tenant2));

    // Enable 2FA purge
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(true);
    when(env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true))
        .thenReturn(true);

    // Self-service purge always succeeds
    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);
    // 2FA purge: tenant1 fails, tenant2 succeeds
    when(purgeService.purgeExpiredTwoFactorAccessTokens())
        .thenThrow(new RuntimeException("twofactor_access_token table missing"))
        .thenReturn(5);

    assertDoesNotThrow(() -> scheduler.purgeExpiredTokens());

    // Self-service purge attempted for both tenants
    verify(purgeService, times(2)).purgeExpiredSelfServiceTokens();
    // 2FA purge attempted for both tenants (first fails, second succeeds)
    verify(purgeService, times(2)).purgeExpiredTwoFactorAccessTokens();
  }

  @Test
  void purgeExpiredTokens_doesNotAbortOnFirstTenantException() {
    FineractPlatformTenant tenant1 = createTenant("bad");
    FineractPlatformTenant tenant2 = createTenant("good");
    when(tenantDetailsService.findAllTenants()).thenReturn(Arrays.asList(tenant1, tenant2));

    // First tenant always throws
    when(purgeService.purgeExpiredSelfServiceTokens())
        .thenThrow(new RuntimeException("bad tenant"))
        .thenReturn(10);

    assertDoesNotThrow(() -> scheduler.purgeExpiredTokens());

    verify(purgeService, times(2)).purgeExpiredSelfServiceTokens();
  }

  // ── Tenant context lifecycle (try/finally) ────────────────────────────────

  @Test
  void purgeExpiredTokens_clearsTenantContextAfterSuccess() {
    FineractPlatformTenant tenant = createTenant("clean-tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));
    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);

    scheduler.purgeExpiredTokens();

    // After the scheduler completes, the tenant context should be cleared.
    // ThreadLocalContextUtil.getTenant() would throw or return null.
    try {
      FineractPlatformTenant leakedTenant = ThreadLocalContextUtil.getTenant();
      // If we get here without exception, the context was NOT cleared properly
      assertNull(leakedTenant, "Tenant context should be cleared after purge");
    } catch (IllegalStateException e) {
      // Expected — getTenant() throws when no tenant is set = context was cleared
    }
  }

  @Test
  void purgeExpiredTokens_clearsTenantContextAfterException() {
    FineractPlatformTenant tenant = createTenant("failing-tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));
    when(purgeService.purgeExpiredSelfServiceTokens())
        .thenThrow(new RuntimeException("unexpected"));

    assertDoesNotThrow(() -> scheduler.purgeExpiredTokens());

    // Tenant context must still be cleared (try/finally contract)
    try {
      FineractPlatformTenant leakedTenant = ThreadLocalContextUtil.getTenant();
      assertNull(leakedTenant, "Tenant context should be cleared even after exception");
    } catch (IllegalStateException e) {
      // Expected — means context was properly cleared
    }
  }

  // ── 2FA conditional logic (isTwoFactorPurgeEnabled) ───────────────────────

  @Test
  void isTwoFactorPurgeEnabled_returnsTrueWhenBothEnabled() {
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(true);
    when(env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true))
        .thenReturn(true);

    assertTrue(scheduler.isTwoFactorPurgeEnabled());
  }

  @Test
  void isTwoFactorPurgeEnabled_returnsFalseWhenCoreDisabled() {
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(false);

    assertFalse(scheduler.isTwoFactorPurgeEnabled());
  }

  @Test
  void isTwoFactorPurgeEnabled_returnsFalseWhenPluginOptOut() {
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(true);
    when(env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true))
        .thenReturn(false);

    assertFalse(scheduler.isTwoFactorPurgeEnabled());
  }

  @Test
  void isTwoFactorPurgeEnabled_returnsFalseWhenBothDisabled() {
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(false);

    assertFalse(scheduler.isTwoFactorPurgeEnabled());
  }

  @Test
  void isTwoFactorPurgeEnabled_coreDisabledTrumpsPluginEnabled() {
    // Even if plugin says "enable 2FA purge", core being disabled means no tokens exist
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(false);

    assertFalse(scheduler.isTwoFactorPurgeEnabled());
    // Plugin property should not even be checked when core is disabled
  }

  // ── 2FA conditional in scheduler flow ─────────────────────────────────────

  @Test
  void purgeExpiredTokens_skips2faWhenCoreDisabled() {
    FineractPlatformTenant tenant = createTenant("tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));
    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);

    // Core 2FA disabled
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(false);

    scheduler.purgeExpiredTokens();

    verify(purgeService, never()).purgeExpiredTwoFactorAccessTokens();
    verify(purgeService, times(1)).purgeExpiredSelfServiceTokens();
  }

  @Test
  void purgeExpiredTokens_skips2faWhenPluginOptOut() {
    FineractPlatformTenant tenant = createTenant("tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));
    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);

    // Core enabled but plugin opt-out
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(true);
    when(env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true))
        .thenReturn(false);

    scheduler.purgeExpiredTokens();

    verify(purgeService, never()).purgeExpiredTwoFactorAccessTokens();
    verify(purgeService, times(1)).purgeExpiredSelfServiceTokens();
  }

  @Test
  void purgeExpiredTokens_purges2faWhenBothEnabled() {
    FineractPlatformTenant tenant = createTenant("tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));
    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);
    when(purgeService.purgeExpiredTwoFactorAccessTokens()).thenReturn(0);

    // Both flags enabled
    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(true);
    when(env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true))
        .thenReturn(true);

    scheduler.purgeExpiredTokens();

    verify(purgeService, times(1)).purgeExpiredTwoFactorAccessTokens();
    verify(purgeService, times(1)).purgeExpiredSelfServiceTokens();
  }

  @Test
  void purgeExpiredTokens_skips2faWhenBothDisabled() {
    FineractPlatformTenant tenant = createTenant("tenant");
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(tenant));
    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);

    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(false);

    scheduler.purgeExpiredTokens();

    verify(purgeService, never()).purgeExpiredTwoFactorAccessTokens();
  }

  // ── Multi-tenant + 2FA combined ───────────────────────────────────────────

  @Test
  void purgeExpiredTokens_purgesBothTablesForEveryTenantWhen2faEnabled() {
    FineractPlatformTenant t1 = createTenant("t1");
    FineractPlatformTenant t2 = createTenant("t2");
    when(tenantDetailsService.findAllTenants()).thenReturn(Arrays.asList(t1, t2));

    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(true);
    when(env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true))
        .thenReturn(true);

    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(2).thenReturn(0);
    when(purgeService.purgeExpiredTwoFactorAccessTokens()).thenReturn(5).thenReturn(3);

    scheduler.purgeExpiredTokens();

    verify(purgeService, times(2)).purgeExpiredSelfServiceTokens();
    verify(purgeService, times(2)).purgeExpiredTwoFactorAccessTokens();
  }

  @Test
  void purgeExpiredTokens_selfServiceOnlyForEveryTenantWhen2faDisabled() {
    FineractPlatformTenant t1 = createTenant("t1");
    FineractPlatformTenant t2 = createTenant("t2");
    FineractPlatformTenant t3 = createTenant("t3");
    when(tenantDetailsService.findAllTenants()).thenReturn(Arrays.asList(t1, t2, t3));

    when(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false)).thenReturn(false);

    when(purgeService.purgeExpiredSelfServiceTokens()).thenReturn(0);

    scheduler.purgeExpiredTokens();

    verify(purgeService, times(3)).purgeExpiredSelfServiceTokens();
    verify(purgeService, never()).purgeExpiredTwoFactorAccessTokens();
  }

  // ── Instance type awareness (isBatchInstance) ─────────────────────────────

  @Test
  void isBatchInstance_returnsTrueWhenBatchWorkerEnabled() {
    when(modeProperties.isBatchWorkerEnabled()).thenReturn(true);
    // isBatchManagerEnabled not stubbed — || short-circuits when worker is true

    assertTrue(scheduler.isBatchInstance());
  }

  @Test
  void isBatchInstance_returnsTrueWhenBatchManagerEnabled() {
    when(modeProperties.isBatchWorkerEnabled()).thenReturn(false);
    when(modeProperties.isBatchManagerEnabled()).thenReturn(true);

    assertTrue(scheduler.isBatchInstance());
  }

  @Test
  void isBatchInstance_returnsFalseWhenNeitherEnabled() {
    when(modeProperties.isBatchWorkerEnabled()).thenReturn(false);
    when(modeProperties.isBatchManagerEnabled()).thenReturn(false);

    assertFalse(scheduler.isBatchInstance());
  }

  @Test
  void isBatchInstance_returnsFalseWhenModeIsNull() {
    when(fineractProperties.getMode()).thenReturn(null);

    assertFalse(scheduler.isBatchInstance());
  }

  @Test
  void purgeExpiredTokens_skipsWhenNotBatchInstance() {
    // Override the default batch-enabled setup
    when(modeProperties.isBatchWorkerEnabled()).thenReturn(false);
    when(modeProperties.isBatchManagerEnabled()).thenReturn(false);

    scheduler.purgeExpiredTokens();

    verify(tenantDetailsService, never()).findAllTenants();
    verify(purgeService, never()).purgeExpiredSelfServiceTokens();
    verify(purgeService, never()).purgeExpiredTwoFactorAccessTokens();
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Creates a real {@link FineractPlatformTenant} instance suitable for setting on {@link
   * ThreadLocalContextUtil}. Uses "UTC" as the timezone, matching the pattern in {@code
   * SelfServiceRegistrationWritePlatformServiceImplTest}.
   */
  private FineractPlatformTenant createTenant(String identifier) {
    return new FineractPlatformTenant(1L, identifier, identifier, "UTC", null);
  }
}
