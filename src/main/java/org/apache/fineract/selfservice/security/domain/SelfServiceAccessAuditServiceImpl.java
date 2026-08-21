/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.selfservice.config.SelfServiceRateLimitProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link SelfServiceAccessAuditService}.
 *
 * <p>Audit writes are asynchronous. Rate-limit checks are synchronous (they gate the request).
 * The nightly purge iterates every tenant so the job is multi-tenant safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceAccessAuditServiceImpl implements SelfServiceAccessAuditService {

  private final SelfServiceAccessAuditRepository auditRepository;
  private final SelfServiceRateLimitProperties rateLimitProperties;
  private final TenantDetailsService tenantDetailsService;

  // -------------------------------------------------------------------------
  // recordAccess
  // -------------------------------------------------------------------------

  @Override
  @Async("notificationExecutor")
  public void recordAccess(final SelfServiceAccessAuditDto auditDto) {
    try {
      SelfServiceAccessAudit entity =
          SelfServiceAccessAudit.builder()
              .appUserId(auditDto.getAppUserId())
              .username(auditDto.getUsername())
              .resourceType(auditDto.getResourceType())
              .resourceId(auditDto.getResourceId())
              .resourceIdentifier(auditDto.getResourceIdentifier())
              .accessResult(auditDto.getAccessResult())
              .endpoint(auditDto.getEndpoint())
              .httpMethod(auditDto.getHttpMethod())
              .ipAddress(auditDto.getIpAddress())
              .createdAt(DateUtils.getOffsetDateTimeOfTenant())
              .build();
      auditRepository.save(entity);
    } catch (Exception e) {
      // Never let audit failures break the main request
      log.warn("Failed to persist access audit record: {}", e.getMessage());
    }
  }

  // -------------------------------------------------------------------------
  // Rate-limit checks (fail-open)
  // -------------------------------------------------------------------------

  @Override
  public boolean isRateLimitExceeded(
      final Long appUserId, final SelfServiceAccessAudit.ResourceType resourceType) {
    try {
      OffsetDateTime windowStart =
          DateUtils.getOffsetDateTimeOfTenant()
              .minusMinutes(rateLimitProperties.windowMinutes());

      long deniedCount =
          auditRepository.countByUserResourceTypeAndResult(
              appUserId,
              resourceType,
              SelfServiceAccessAudit.AccessResult.DENIED,
              windowStart);

      return deniedCount >= rateLimitProperties.perResource();
    } catch (Exception e) {
      // INTENTIONAL FAIL-OPEN
      log.warn(
          "Rate-limit check failed for user={} resourceType={} (fail-open)",
          appUserId,
          resourceType,
          e);
      return false;
    }
  }

  @Override
  public boolean isGlobalRateLimitExceeded(final Long appUserId) {
    try {
      OffsetDateTime windowStart =
          DateUtils.getOffsetDateTimeOfTenant()
              .minusMinutes(rateLimitProperties.windowMinutes());

      long deniedCount =
          auditRepository.countByUserAndResult(
              appUserId, SelfServiceAccessAudit.AccessResult.DENIED, windowStart);

      return deniedCount >= rateLimitProperties.global();
    } catch (Exception e) {
      // INTENTIONAL FAIL-OPEN
      log.warn("Global rate-limit check failed for user={} (fail-open)", appUserId, e);
      return false;
    }
  }

  // -------------------------------------------------------------------------
  // Nightly multi-tenant purge
  // -------------------------------------------------------------------------

  /**
   * Runs once per day at 02:00. Iterates every tenant so the job is fully multi-tenant.
   * Failures in one tenant never abort the others.
   */
  @Scheduled(cron = "0 0 2 * * ?")
  public void purgeExpiredAuditRecords() {
    final int retentionDays = rateLimitProperties.retentionDays(); // isolated lookup
    final OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);

    log.info(
        "Starting access-audit purge (retention={} days, cutoff={})", retentionDays, cutoff);

    List<FineractPlatformTenant> tenants;
    try {
      tenants = tenantDetailsService.findAllTenants();
    } catch (Exception e) {
      log.error("Unable to load tenant list – aborting purge", e);
      return;
    }

    for (FineractPlatformTenant tenant : tenants) {
      try {
        ThreadLocalContextUtil.setTenant(tenant);
        purgeForCurrentTenant(cutoff, retentionDays);
      } catch (Exception e) {
        log.error(
            "Failed to purge access-audit records for tenant {}",
            tenant.getTenantIdentifier(),
            e);
      } finally {
        ThreadLocalContextUtil.clearTenant();
      }
    }

    log.info("Access-audit purge finished for {} tenant(s)", tenants.size());
  }

  /**
   * Performs the actual delete for the tenant currently set in ThreadLocalContextUtil.
   * Runs in its own transaction.
   */
  @Transactional
  protected void purgeForCurrentTenant(OffsetDateTime cutoff, int retentionDays) {
    String tenantId = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
    log.info(
        "Purging access audit records older than {} days for tenant {} (cutoff: {})",
        retentionDays,
        tenantId,
        cutoff);

    // Repository method returns void
    auditRepository.deleteByCreatedAtBefore(cutoff);

    log.info("Access-audit purge completed for tenant {}", tenantId);
  }
}