/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link SelfServiceAccessAudit}.
 *
 * <p>Multi-tenant: operates on the tenant-scoped datasource routed by Fineract's {@code
 * TenantAwareRoutingDataSource}. No explicit tenant_id column is needed because each tenant has its
 * own schema/database.
 *
 * <p><b>EclipseLink compatibility:</b> Fineract uses EclipseLink (not Hibernate) as its JPA
 * provider. EclipseLink does NOT coerce JPQL string literals (e.g., {@code 'DENIED'}) to enum
 * types. All enum comparisons MUST use typed parameters, never inline string literals. Using a
 * string literal like {@code a.accessResult = 'DENIED'} causes:
 *
 * <pre>
 * java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Enum
 *   at o.e.p.mappings.converters.EnumTypeConverter.convertObjectValueToDataValue
 * </pre>
 */
@Repository
public interface SelfServiceAccessAuditRepository
    extends JpaRepository<SelfServiceAccessAudit, Long> {

  /**
   * Counts attempts for a specific user + resource type + result within a time window. Used for
   * rate-limiting / brute-force detection.
   *
   * <p>The {@code accessResult} parameter MUST be the actual enum value (e.g., {@code
   * SelfServiceAccessAudit.AccessResult.DENIED}), not a string. EclipseLink's {@code
   * EnumTypeConverter} requires the parameter type to match the entity field type exactly.
   */
  @Query(
      """
        SELECT COUNT(a) FROM SelfServiceAccessAudit a
        WHERE a.appUserId = :appUserId
          AND a.resourceType = :resourceType
          AND a.accessResult = :accessResult
          AND a.createdAt >= :since
        """)
  long countByUserResourceTypeAndResult(
      @Param("appUserId") Long appUserId,
      @Param("resourceType") SelfServiceAccessAudit.ResourceType resourceType,
      @Param("accessResult") SelfServiceAccessAudit.AccessResult accessResult,
      @Param("since") OffsetDateTime since);

  /**
   * Counts attempts for a specific user across ALL resource types within a window.
   *
   * <p>Same EclipseLink constraint: {@code accessResult} must be a typed enum parameter.
   */
  @Query(
      """
        SELECT COUNT(a) FROM SelfServiceAccessAudit a
        WHERE a.appUserId = :appUserId
          AND a.accessResult = :accessResult
          AND a.createdAt >= :since
        """)
  long countByUserAndResult(
      @Param("appUserId") Long appUserId,
      @Param("accessResult") SelfServiceAccessAudit.AccessResult accessResult,
      @Param("since") OffsetDateTime since);

  /**
   * Deletes all audit records older than the specified date.
   * Used by automated purge mechanisms to enforce retention policies.
   */
  void deleteByCreatedAtBefore(OffsetDateTime dateTime);
}
