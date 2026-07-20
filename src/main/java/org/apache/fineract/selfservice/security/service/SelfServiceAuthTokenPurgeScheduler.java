/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationTokenRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "mifos.self.service.token.purge.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SelfServiceAuthTokenPurgeScheduler {

  private final SelfServiceAuthenticationTokenRepository repository;
  private final TenantDetailsService tenantDetailsService;
  private final FineractProperties fineractProperties;
  private final TransactionTemplate transactionTemplate; // Injected by Spring Boot

  @Scheduled(cron = "${mifos.self.service.token.purge.cron:0 0 * * * *}")
  public void purgeExpiredTokens() {
    // Ensure this only runs on batch-enabled instances to comply with Fineract's architecture
    if (!isBatchInstance()) {
      log.debug("Skipping expired auth token purge — this instance is not batch-enabled");
      return;
    }

    log.info("Starting expired auth token purge cycle");
    List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();

    for (FineractPlatformTenant tenant : tenants) {
      try {
        // CRITICAL: Set the tenant context
        ThreadLocalContextUtil.setTenant(tenant);

        // Execute delete inside a transaction for the current tenant
        transactionTemplate.execute(
            status -> {
              repository.deleteByExpiresAtBefore(LocalDateTime.now());
              return null;
            });

        log.debug(
            "Successfully purged expired tokens for tenant: {}", tenant.getTenantIdentifier());
      } catch (Exception e) {
        log.error(
            "Expired auth token purge failed for tenant: {}", tenant.getTenantIdentifier(), e);
        // Continue with next tenant
      } finally {
        // CRITICAL: Always clear tenant context to prevent leakage
        ThreadLocalContextUtil.clearTenant();
      }
    }

    log.info("Expired auth token purge cycle completed for {} tenant(s)", tenants.size());
  }

  boolean isBatchInstance() {
    FineractProperties.FineractModeProperties mode = fineractProperties.getMode();
    return mode != null && (mode.isBatchWorkerEnabled() || mode.isBatchManagerEnabled());
  }
}
