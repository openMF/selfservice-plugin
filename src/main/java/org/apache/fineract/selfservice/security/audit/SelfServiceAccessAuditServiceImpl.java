/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.audit;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link SelfServiceAccessAuditService}.
 *
 * <p>Audit writes are asynchronous to avoid impacting request latency.
 * Rate-limit checks are synchronous (they gate the request).
 *
 * <p>Multi-tenant: the repository operates on the tenant-scoped datasource.
 * Rate-limit thresholds are configurable per deployment via application properties.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceAccessAuditServiceImpl implements SelfServiceAccessAuditService {

    private final SelfServiceAccessAuditRepository auditRepository;

    /** Max denied attempts per resource type within the window before rate-limiting. */
    @Value("${fineract.selfservice.security.rate-limit.per-resource:10}")
    private int maxDeniedPerResource;

    /** Max denied attempts across all resource types within the window. */
    @Value("${fineract.selfservice.security.rate-limit.global:25}")
    private int maxDeniedGlobal;

    /** Time window in minutes for rate-limit counting. */
    @Value("${fineract.selfservice.security.rate-limit.window-minutes:15}")
    private int rateLimitWindowMinutes;

    @Override
    @Async
    public void recordAccess(final SelfServiceAccessAuditDto auditDto) {
        try {
            SelfServiceAccessAudit entity = SelfServiceAccessAudit.builder()
                    .appUserId(auditDto.getAppUserId())
                    .username(auditDto.getUsername())
                    .resourceType(auditDto.getResourceType())
                    .resourceId(auditDto.getResourceId())
                    .resourceIdentifier(auditDto.getResourceIdentifier())
                    .accessResult(auditDto.getAccessResult())
                    .endpoint(auditDto.getEndpoint())
                    .httpMethod(auditDto.getHttpMethod())
                    .ipAddress(auditDto.getIpAddress())
                    .createdAt(auditDto.getTimestamp() != null
                            ? auditDto.getTimestamp()
                            : DateUtils.getOffsetDateTimeOfTenant())
                    .build();

            auditRepository.save(entity);

            if (auditDto.getAccessResult() == SelfServiceAccessAudit.AccessResult.DENIED) {
                log.warn("SECURITY AUDIT [DENIED]: user={} resource={}:{} endpoint={} ip={}",
                        auditDto.getUsername(),
                        auditDto.getResourceType(),
                        auditDto.getResourceId() != null
                                ? auditDto.getResourceId()
                                : auditDto.getResourceIdentifier(),
                        auditDto.getEndpoint(),
                        auditDto.getIpAddress());
            }
        } catch (Exception e) {
            // Audit failure must NEVER propagate to the caller
            log.error("Failed to persist access audit record (non-fatal)", e);
        }
    }

    @Override
    public boolean isRateLimitExceeded(final Long appUserId,
                                       final SelfServiceAccessAudit.ResourceType resourceType) {
        try {
            OffsetDateTime windowStart = DateUtils.getOffsetDateTimeOfTenant()
                    .minusMinutes(rateLimitWindowMinutes);

            // ═══════════════════════════════════════════════════════════
            // FIX: Pass AccessResult.DENIED as a typed enum parameter.
            // EclipseLink requires the parameter type to match the entity
            // field type. A string literal 'DENIED' in JPQL causes:
            //   ClassCastException: String cannot be cast to Enum
            // ═══════════════════════════════════════════════════════════
            long deniedCount = auditRepository.countByUserResourceTypeAndResult(
                    appUserId,
                    resourceType,
                    SelfServiceAccessAudit.AccessResult.DENIED,
                    windowStart);

            return deniedCount >= maxDeniedPerResource;
        } catch (Exception e) {
            log.warn("Rate-limit check failed for user={} resourceType={} (fail-open)",
                    appUserId, resourceType, e);
            return false;
        }
    }

    @Override
    public boolean isGlobalRateLimitExceeded(final Long appUserId) {
        try {
            OffsetDateTime windowStart = DateUtils.getOffsetDateTimeOfTenant()
                    .minusMinutes(rateLimitWindowMinutes);

            // ═══════════════════════════════════════════════════════════
            // FIX: Same — pass enum parameter, not string literal
            // ═══════════════════════════════════════════════════════════
            long deniedCount = auditRepository.countByUserAndResult(
                    appUserId,
                    SelfServiceAccessAudit.AccessResult.DENIED,
                    windowStart);

            return deniedCount >= maxDeniedGlobal;
        } catch (Exception e) {
            log.warn("Global rate-limit check failed for user={} (fail-open)", appUserId, e);
            return false;
        }
    }
}