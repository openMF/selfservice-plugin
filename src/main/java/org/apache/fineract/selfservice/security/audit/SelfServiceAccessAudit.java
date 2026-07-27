/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * JPA entity for the {@code m_selfservice_access_audit} table. Records every ownership validation
 * attempt (granted or denied) for security monitoring, forensic analysis, and rate-limiting.
 *
 * <p>Multi-tenant: table is created per-tenant via Liquibase tenant changelog. The tenant
 * datasource routing is handled by Fineract's TenantAwareRoutingDataSource.
 */
@Entity
@Table(name = "m_selfservice_access_audit")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfServiceAccessAudit extends AbstractPersistableCustom<Long>{

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "appuser_id", nullable = false)
  private Long appUserId;

  @Column(name = "username", length = 100)
  private String username;

  @Enumerated(EnumType.STRING)
  @Column(name = "resource_type", length = 50, nullable = false)
  private ResourceType resourceType;

  @Column(name = "resource_id")
  private Long resourceId;

  @Column(name = "resource_identifier", length = 255)
  private String resourceIdentifier;

  @Enumerated(EnumType.STRING)
  @Column(name = "access_result", length = 20, nullable = false)
  private AccessResult accessResult;

  @Column(name = "endpoint", length = 255)
  private String endpoint;

  @Column(name = "http_method", length = 10)
  private String httpMethod;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public enum ResourceType {
    CLIENT,
    SAVINGS_ACCOUNT,
    LOAN_ACCOUNT,
    SHARE_ACCOUNT,
    TRANSFER_SOURCE,
    TRANSFER_DESTINATION,
    BENEFICIARY,
    POCKET
  }

  public enum AccessResult {
    GRANTED,
    DENIED
  }

  /** Factory method for a DENIED access attempt. */
  public static SelfServiceAccessAudit denied(
      final Long appUserId,
      final String username,
      final ResourceType resourceType,
      final Long resourceId,
      final String resourceIdentifier,
      final String endpoint,
      final String httpMethod,
      final String ipAddress) {
    return SelfServiceAccessAudit.builder()
        .appUserId(appUserId)
        .username(username)
        .resourceType(resourceType)
        .resourceId(resourceId)
        .resourceIdentifier(resourceIdentifier)
        .accessResult(AccessResult.DENIED)
        .endpoint(endpoint)
        .httpMethod(httpMethod)
        .ipAddress(ipAddress)
        .createdAt(DateUtils.getOffsetDateTimeOfTenant())
        .build();
  }

  /** Factory method for a GRANTED access attempt. */
  public static SelfServiceAccessAudit granted(
      final Long appUserId,
      final String username,
      final ResourceType resourceType,
      final Long resourceId,
      final String endpoint,
      final String httpMethod,
      final String ipAddress) {
    return SelfServiceAccessAudit.builder()
        .appUserId(appUserId)
        .username(username)
        .resourceType(resourceType)
        .resourceId(resourceId)
        .accessResult(AccessResult.GRANTED)
        .endpoint(endpoint)
        .httpMethod(httpMethod)
        .ipAddress(ipAddress)
        .createdAt(DateUtils.getOffsetDateTimeOfTenant())
        .build();
  }
}
