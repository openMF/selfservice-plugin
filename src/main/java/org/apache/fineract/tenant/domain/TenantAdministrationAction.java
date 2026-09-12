/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

/** The tenant administration actions recorded in the audit trail. */
public enum TenantAdministrationAction {
  CREATE,
  UPDATE,
  ACTIVATE,
  DEACTIVATE,
  SUSPEND,
  DELETE;

  /**
   * @param status the status a tenant is moving to
   * @return the action that records that move
   */
  public static TenantAdministrationAction forStatusChange(final TenantStatus status) {
    return switch (status) {
      case ACTIVE -> ACTIVATE;
      case INACTIVE -> DEACTIVATE;
      case SUSPENDED -> SUSPEND;
    };
  }
}
