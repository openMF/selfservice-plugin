/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class TenantBrandingTest {

  @Test
  void newRowDefaultsToBlue() {
    // A tenant that has never chosen a colour still renders.
    assertEquals("blue", new TenantBranding().getPrimaryColor());
  }

  @Test
  void persistStampsBothAuditColumns() {
    final TenantBranding branding = new TenantBranding();

    branding.onCreate();

    assertNotNull(branding.getCreatedAt());
    assertNotNull(branding.getUpdatedAt());
    assertEquals(branding.getCreatedAt(), branding.getUpdatedAt());
  }

  @Test
  void updateMovesUpdatedAtAndLeavesCreatedAtAlone() {
    final TenantBranding branding = new TenantBranding();
    branding.onCreate();
    final OffsetDateTime createdAt = branding.getCreatedAt();
    // Backdate so the new stamp is distinguishable regardless of clock
    // granularity.
    branding.setUpdatedAt(createdAt.minusHours(1));

    branding.onUpdate();

    assertEquals(createdAt, branding.getCreatedAt());
    assertTrue(branding.getUpdatedAt().isAfter(createdAt.minusHours(1)));
  }
}
