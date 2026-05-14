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

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that purges expired self-service and 2FA tokens across all tenants.
 *
 * <p>Enabled by default; disable entirely via {@code mifos.self.service.token.purge.enabled=false}.
 *
 * <p><strong>Instance type awareness:</strong> Per Fineract's instance type architecture, batch
 * jobs should only run on batch-enabled instances. This scheduler checks {@link
 * FineractProperties.FineractModeProperties#isBatchWorkerEnabled()} at runtime and skips execution
 * on read-only or write-only instances. See <a
 * href="https://fineract.apache.org/docs/current/#_configuring_instance_type_via_environment_variables">
 * Fineract Instance Types</a>.
 *
 * <p><strong>Multi-instance safety:</strong> The purge queries are idempotent {@code DELETE WHERE
 * expired < cutoff} statements. If multiple batch instances load this plugin, concurrent execution
 * is safe — worst case some queries delete 0 rows.
 *
 * <p><strong>Tenant context:</strong> Each tenant is processed in its own {@code try/finally} block
 * that sets and clears {@link ThreadLocalContextUtil}. This is mandatory because Spring's
 * {@code @Scheduled} thread pool reuses threads — a leaked tenant context would silently corrupt
 * subsequent executions on the same thread.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "mifos.self.service.token.purge.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ExpiredTokenPurgeScheduler {

  private final TenantDetailsService tenantDetailsService;
  private final ExpiredTokenPurgeService purgeService;
  private final Environment env;
  private final FineractProperties fineractProperties;

  /**
   * Entry point invoked by Spring's task scheduler at the configured cron interval.
   *
   * <p>Default cron: every hour on the hour ({@code 0 0 * * * *}).
   */
  @Scheduled(cron = "${mifos.self.service.token.purge.cron:0 0 * * * *}")
  public void purgeExpiredTokens() {
    if (!isBatchInstance()) {
      log.debug("Skipping expired token purge — this instance is not batch-enabled");
      return;
    }
    log.info("Starting expired token purge cycle");
    List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();

    for (FineractPlatformTenant tenant : tenants) {
      try {
        ThreadLocalContextUtil.setTenant(tenant);

        purgeService.purgeExpiredSelfServiceTokens();

        if (isTwoFactorPurgeEnabled()) {
          purgeService.purgeExpiredTwoFactorAccessTokens();
        }
      } catch (Exception e) {
        log.error("Expired token purge failed for tenant: {}", tenant.getTenantIdentifier(), e);
        // Continue to next tenant — one failure must not block others
      } finally {
        ThreadLocalContextUtil.clearTenant();
      }
    }
    log.info("Expired token purge cycle completed for {} tenant(s)", tenants.size());
  }

  /**
   * 2FA purge requires <strong>both</strong> conditions to be true:
   *
   * <ol>
   *   <li>Fineract core has 2FA enabled ({@code fineract.security.2fa.enabled=true}) — if core 2FA
   *       is disabled, the {@code twofactor_access_token} table is not populated and purging it is
   *       a no-op.
   *   <li>The plugin's own 2FA purge switch is not explicitly disabled ({@code
   *       mifos.self.service.token.purge.2fa.enabled != false}) — allows operators to manage
   *       cleanup externally if desired.
   * </ol>
   */
  boolean isTwoFactorPurgeEnabled() {
    boolean coreTwoFactorEnabled =
        Boolean.TRUE.equals(env.getProperty("fineract.security.2fa.enabled", Boolean.class, false));
    if (!coreTwoFactorEnabled) {
      return false;
    }
    boolean pluginTwoFactorPurgeEnabled =
        !Boolean.FALSE.equals(
            env.getProperty("mifos.self.service.token.purge.2fa.enabled", Boolean.class, true));
    return pluginTwoFactorPurgeEnabled;
  }

  /**
   * Returns {@code true} if this Fineract instance is configured for batch processing.
   *
   * <p>Per Fineract's instance type architecture, scheduled jobs should only run on
   * batch-worker-enabled or batch-manager-enabled instances. Read-only and write-only instances
   * must not execute background jobs.
   *
   * @see FineractProperties.FineractModeProperties
   */
  boolean isBatchInstance() {
    FineractProperties.FineractModeProperties mode = fineractProperties.getMode();
    return mode != null && (mode.isBatchWorkerEnabled() || mode.isBatchManagerEnabled());
  }
}
