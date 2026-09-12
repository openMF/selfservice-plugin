/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TenantAdministrationActionTest {

  @Test
  void eachStatusMapsToItsOwnAction() {
    assertEquals(
        TenantAdministrationAction.ACTIVATE,
        TenantAdministrationAction.forStatusChange(TenantStatus.ACTIVE));
    assertEquals(
        TenantAdministrationAction.DEACTIVATE,
        TenantAdministrationAction.forStatusChange(TenantStatus.INACTIVE));
    assertEquals(
        TenantAdministrationAction.SUSPEND,
        TenantAdministrationAction.forStatusChange(TenantStatus.SUSPENDED));
  }

  @ParameterizedTest
  @EnumSource(TenantStatus.class)
  void everyStatusIsMapped(final TenantStatus status) {
    // The switch is exhaustive over the enum, so a status added later fails to
    // compile here rather than slipping through unaudited.
    org.junit.jupiter.api.Assertions.assertNotNull(
        TenantAdministrationAction.forStatusChange(status));
  }
}
