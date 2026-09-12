/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.tenant.domain.TenantAdministrationAction;
import org.apache.fineract.tenant.security.TenantMasterAccess;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Records tenant administration actions to the audit trail in the tenant store.
 *
 * <p>MX-406 requires audit logging of all tenant management actions. Application logs are not that:
 * they rotate away, and they are not queryable by an auditor. This writes a durable row per action
 * beside the registry it describes, so the trail outlives any tenant it mentions.
 *
 * <p><strong>Nothing secret is recorded.</strong> {@code detail} carries the names of the fields a
 * request changed, never their values, so a password rotation is recorded as having happened while
 * the password itself never reaches the table - SOUL_GUARDRAILS: minimise sensitive data in logs,
 * and redact confidential fields before recording them.
 *
 * <p>A failure to write the trail never fails the action that was being recorded. Losing an audit
 * row is bad; rolling back a completed tenant change because its bookkeeping failed, and leaving
 * the registry inconsistent with what the caller was told, is worse.
 */
@Service
@Slf4j
public class TenantAdministrationAuditService {

  /** Recorded when an action completed. */
  private static final String OUTCOME_SUCCESS = "SUCCESS";

  /** Recorded when an action was attempted and failed. */
  private static final String OUTCOME_FAILURE = "FAILURE";

  private static final int MAX_DETAIL_LENGTH = 1000;

  private final JdbcTemplate jdbcTemplate;

  public TenantAdministrationAuditService(
      @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {
    this.jdbcTemplate = new JdbcTemplate(tenantStoreDataSource);
  }

  /** Records an action that completed. */
  public void recordSuccess(
      final TenantAdministrationAction action,
      final String tenantIdentifier,
      final Long tenantId,
      final String detail) {
    record(action, OUTCOME_SUCCESS, tenantIdentifier, tenantId, detail);
  }

  /** Records an action that was attempted and failed. */
  public void recordFailure(
      final TenantAdministrationAction action,
      final String tenantIdentifier,
      final Long tenantId,
      final String detail) {
    record(action, OUTCOME_FAILURE, tenantIdentifier, tenantId, detail);
  }

  private void record(
      final TenantAdministrationAction action,
      final String outcome,
      final String tenantIdentifier,
      final Long tenantId,
      final String detail) {
    try {
      // created_at is zone-less; it is written as a UTC wall-clock value so every node
      // records the same value for the same instant, whatever its JVM time zone.
      jdbcTemplate.update(
          "insert into tenant_administration_audit (action, outcome, tenant_identifier, tenant_id,"
              + " performed_by, performed_by_tenant, detail, created_at)"
              + " values (?, ?, ?, ?, ?, ?, ?, ?)",
          action.name(),
          outcome,
          tenantIdentifier,
          tenantId,
          currentUsername(),
          // Master users act outside every tenant, so no acting tenant is recorded.
          null,
          truncated(detail),
          LocalDateTime.now(ZoneOffset.UTC));
    } catch (final RuntimeException e) {
      log.error(
          "Could not record tenant administration audit for {} on tenant {}",
          action,
          tenantIdentifier,
          e);
    }
  }

  /**
   * @return the acting master user's name, or null when the request is not authenticated as one -
   *     the row is still written, because an unattributed action is more worth recording than not
   *     recording it
   */
  private String currentUsername() {
    return TenantMasterAccess.currentSuperMaster().orElse(null);
  }

  /** Keeps detail inside the column, so an oversized value cannot fail the insert. */
  private static String truncated(final String detail) {
    if (detail == null || detail.length() <= MAX_DETAIL_LENGTH) {
      return detail;
    }
    return detail.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
  }
}
