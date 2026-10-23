/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the activity history older than each tenant's own {@code retentionDays} (default 90,
 * configurable per tenant in the external service {@value ActivitySettings#SERVICE_NAME}, because
 * some regions require longer retention).
 *
 * <p>Follows the pattern of the step-up and token purge jobs: batch instances only, every tenant in
 * its own {@code try/finally} that sets and clears the tenant context, a failure in one tenant
 * never stops the others.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "mifos.self.service.activity.purge.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ActivityPurgeScheduler {

  private final SelfServiceActivityRepository repository;
  private final ActivitySettingsProvider settingsProvider;
  private final TenantDetailsService tenantDetailsService;
  private final FineractProperties fineractProperties;
  private final TransactionTemplate transactionTemplate;
  private final TransactionDateUtil transactionDateUtil;

  @Scheduled(cron = "${mifos.self.service.activity.purge.cron:0 45 3 * * *}")
  public void purgeOldActivity() {
    if (!isBatchInstance()) {
      log.debug("Skipping activity purge: this instance is not batch-enabled");
      return;
    }
    final List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();
    for (final FineractPlatformTenant tenant : tenants) {
      try {
        ThreadLocalContextUtil.setTenant(tenant);
        purgeCurrentTenant();
      } catch (Exception e) {
        log.error("Activity purge failed for tenant: {}", tenant.getTenantIdentifier(), e);
      } finally {
        ThreadLocalContextUtil.clearTenant();
      }
    }
  }

  /** Purges the tenant bound to the current thread; returns how many rows were removed. */
  int purgeCurrentTenant() {
    final int days = settingsProvider.current().retentionDays();
    final LocalDateTime cutoff =
        transactionDateUtil.getCurrentTenantLocalDateTime().minusDays(days);
    final Integer deleted =
        transactionTemplate.execute(status -> repository.deleteCreatedBefore(cutoff));
    final int removed = deleted == null ? 0 : deleted;
    log.info("Activity purge: {} row(s) older than {} day(s) removed", removed, days);
    return removed;
  }

  boolean isBatchInstance() {
    final FineractProperties.FineractModeProperties mode = fineractProperties.getMode();
    return mode != null && (mode.isBatchWorkerEnabled() || mode.isBatchManagerEnabled());
  }
}
