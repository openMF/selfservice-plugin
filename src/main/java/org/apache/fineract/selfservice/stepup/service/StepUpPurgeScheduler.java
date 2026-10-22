/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.stepup.domain.StepUpChallengeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes step-up challenges older than the tenant's {@code retentionDays}. Challenge rows are the
 * audit trail of step-up (US-E12-05), so they are kept for a configurable period (default 30 days)
 * and not removed as soon as they expire.
 *
 * <p>Follows the pattern of {@code SelfServiceAuthTokenPurgeScheduler}: batch instances only, every
 * tenant in its own {@code try/finally} that sets and clears the tenant context.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "mifos.self.service.stepup.purge.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StepUpPurgeScheduler {

  private final StepUpChallengeRepository repository;
  private final StepUpSettingsProvider settingsProvider;
  private final TenantDetailsService tenantDetailsService;
  private final FineractProperties fineractProperties;
  private final TransactionTemplate transactionTemplate;
  private final TransactionDateUtil transactionDateUtil;

  @Scheduled(cron = "${mifos.self.service.stepup.purge.cron:0 30 3 * * *}")
  public void purgeOldChallenges() {
    if (!isBatchInstance()) {
      log.debug("Skipping step-up challenge purge: this instance is not batch-enabled");
      return;
    }
    final List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();
    for (final FineractPlatformTenant tenant : tenants) {
      try {
        ThreadLocalContextUtil.setTenant(tenant);
        final int days = settingsProvider.current().retentionDays();
        final LocalDateTime cutoff =
            transactionDateUtil.getCurrentTenantLocalDateTime().minusDays(days);
        final Integer deleted =
            transactionTemplate.execute(status -> repository.deleteCreatedBefore(cutoff));
        log.info(
            "Step-up purge for tenant {}: {} challenge(s) older than {} day(s) removed",
            tenant.getTenantIdentifier(),
            deleted,
            days);
      } catch (Exception e) {
        log.error("Step-up challenge purge failed for tenant: {}", tenant.getTenantIdentifier(), e);
      } finally {
        ThreadLocalContextUtil.clearTenant();
      }
    }
  }

  boolean isBatchInstance() {
    final FineractProperties.FineractModeProperties mode = fineractProperties.getMode();
    return mode != null && (mode.isBatchWorkerEnabled() || mode.isBatchManagerEnabled());
  }
}
