/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.fineract.selfservice.config.SelfServiceRateLimitProperties;

/**
 * Implementation of {@link SelfServiceAccessAuditService}.
 *
 * <p>Audit writes are asynchronous to avoid impacting request latency. Rate-limit checks are
 * synchronous (they gate the request).
 *
 * <p>Multi-tenant: the repository operates on the tenant-scoped datasource. Rate-limit thresholds
 * are configurable per deployment via application properties.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceAccessAuditServiceImpl implements SelfServiceAccessAuditService {

  private final SelfServiceAccessAuditRepository auditRepository;
  private final SelfServiceRateLimitProperties rateLimitProperties;

  /**
   * Records an access audit event asynchronously.
   * This method captures the context of resource access (both allowed and denied).
   * Any exceptions during persistence are swallowed to prevent audit logging failures
   * from impacting the main transaction.
   * 
   * @param auditDto the details of the access event
   */
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
              .createdAt(
                  auditDto.getTimestamp() != null
                      ? auditDto.getTimestamp()
                      : DateUtils.getOffsetDateTimeOfTenant())
              .build();

      auditRepository.save(entity);

      if (auditDto.getAccessResult() == SelfServiceAccessAudit.AccessResult.DENIED) {
        log.warn(
            "SECURITY AUDIT [DENIED]: resource={} endpoint={}",
            auditDto.getResourceType(),
            auditDto.getEndpoint());
      }
    } catch (Exception e) {
      // Audit failure must NEVER propagate to the caller
      log.error("Failed to persist access audit record (non-fatal)", e);
    }
  }

  /**
   * Checks if the per-resource rate limit for denied accesses has been exceeded.
   * <p>
   * Fail-open behavior: If the database query fails, this method returns false,
   * allowing the request to proceed. This prevents a database outage from
   * locking out all legitimate traffic.
   *
   * @param appUserId    the user attempting access
   * @param resourceType the specific type of resource
   * @return true if the limit is exceeded, false otherwise (including on error)
   */
  @Override
  public boolean isRateLimitExceeded(
      final Long appUserId, final SelfServiceAccessAudit.ResourceType resourceType) {
    try {
      OffsetDateTime windowStart =
          DateUtils.getOffsetDateTimeOfTenant().minusMinutes(rateLimitProperties.windowMinutes());

      // ═══════════════════════════════════════════════════════════
      // FIX: Pass AccessResult.DENIED as a typed enum parameter.
      // EclipseLink requires the parameter type to match the entity
      // field type. A string literal 'DENIED' in JPQL causes:
      //   ClassCastException: String cannot be cast to Enum
      // ═══════════════════════════════════════════════════════════
      long deniedCount =
          auditRepository.countByUserResourceTypeAndResult(
              appUserId, resourceType, SelfServiceAccessAudit.AccessResult.DENIED, windowStart);

      return deniedCount >= rateLimitProperties.perResource();
    } catch (Exception e) {
      // INTENTIONAL FAIL-OPEN: If the datasource or query fails, we log a warning but return false
      // to allow the request to proceed. This ensures that an audit-database outage does not
      // inadvertently lock out all legitimate traffic (denial of service).
      log.warn(
          "Rate-limit check failed for user={} resourceType={} (fail-open)",
          appUserId,
          resourceType,
          e);
      return false;
    }
  }

  /**
   * Checks if the global rate limit for all denied accesses has been exceeded for a user.
   * <p>
   * Fail-open behavior: If the database query fails, this method returns false,
   * allowing the request to proceed. This prevents an audit-database outage
   * from locking out legitimate traffic.
   *
   * @param appUserId the user attempting access
   * @return true if the global limit is exceeded, false otherwise (including on error)
   */
  @Override
  public boolean isGlobalRateLimitExceeded(final Long appUserId) {
    try {
      OffsetDateTime windowStart =
          DateUtils.getOffsetDateTimeOfTenant().minusMinutes(rateLimitProperties.windowMinutes());

      // ═══════════════════════════════════════════════════════════
      // FIX: Same — pass enum parameter, not string literal
      // ═══════════════════════════════════════════════════════════
      long deniedCount =
          auditRepository.countByUserAndResult(
              appUserId, SelfServiceAccessAudit.AccessResult.DENIED, windowStart);

      return deniedCount >= rateLimitProperties.global();
    } catch (Exception e) {
      // INTENTIONAL FAIL-OPEN: Same as above. An audit system failure should not break core banking availability.
      log.warn("Global rate-limit check failed for user={} (fail-open)", appUserId, e);
      return false;
    }
  }

  /**
   * Automated purge mechanism to enforce the approved retention period for personal-data fields
   * in the access audit log. Runs daily at 2 AM.
   */
  @Scheduled(cron = "0 0 2 * * ?")
  @Transactional
  public void purgeExpiredAuditRecords() {
    try {
      OffsetDateTime cutoff = OffsetDateTime.now().minusDays(rateLimitProperties.retentionDays());
      log.info("Purging access audit records older than {} days (cutoff: {})", rateLimitProperties.retentionDays(), cutoff);
      auditRepository.deleteByCreatedAtBefore(cutoff);
    } catch (Exception e) {
      log.error("Failed to purge expired access audit records", e);
    }
  }
}
