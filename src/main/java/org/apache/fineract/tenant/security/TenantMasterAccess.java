/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.security;

import java.util.Optional;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The super master role, and the check every tenant administration operation makes against it.
 *
 * <p>{@link TenantManagementSecurityConfiguration} already restricts {@code /v1/admin/tenants} to
 * this role. The resource checks again on each call so that a change to the chain's path matching
 * can never silently expose these operations: the endpoint fails closed on its own.
 */
public final class TenantMasterAccess {

  /** Role held by master users. Stored without Spring's {@code ROLE_} prefix. */
  public static final String SUPER_MASTER_ROLE = "SUPER_MASTER";

  static final String SUPER_MASTER_AUTHORITY = "ROLE_" + SUPER_MASTER_ROLE;

  private TenantMasterAccess() {}

  /**
   * @return the authenticated super master's username, or empty when the current request is not
   *     authenticated as one
   */
  public static Optional<String> currentSuperMaster() {
    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    final boolean superMaster =
        authentication.getAuthorities().stream()
            .anyMatch(authority -> SUPER_MASTER_AUTHORITY.equals(authority.getAuthority()));
    return superMaster ? Optional.ofNullable(authentication.getName()) : Optional.empty();
  }

  /**
   * @return the authenticated super master's username
   * @throws NoAuthorizationException when the request is not authenticated as a super master
   */
  public static String requireSuperMaster() {
    return currentSuperMaster()
        .orElseThrow(
            () -> new NoAuthorizationException("Tenant administration requires a master user"));
  }
}
