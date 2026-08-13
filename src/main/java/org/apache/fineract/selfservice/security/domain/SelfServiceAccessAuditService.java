/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

/**
 * Service interface for recording and querying self-service access audit events. All methods are
 * multi-tenant safe (operate on tenant-routed datasource).
 */
public interface SelfServiceAccessAuditService {

  /**
   * Records an access audit event asynchronously. Never throws — audit failures must not block the
   * main request flow.
   */
  void recordAccess(SelfServiceAccessAuditDto auditDto);

  /**
   * Checks if the user has exceeded the denied-attempt threshold for a specific resource type
   * within the configured time window.
   *
   * @return true if the user should be temporarily blocked
   */
  boolean isRateLimitExceeded(Long appUserId, SelfServiceAccessAudit.ResourceType resourceType);

  /**
   * Checks if the user has exceeded the global denied-attempt threshold across all resource types.
   */
  boolean isGlobalRateLimitExceeded(Long appUserId);
}
