/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TenantUpdateRequestTest {

  private static TenantUpdateRequest empty() {
    return new TenantUpdateRequest(null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void aRequestWithNothingSetChangesNothing() {
    assertTrue(empty().isEmpty());
    assertEquals(List.of(), empty().changedFieldNames());
  }

  @Test
  void anyOneFieldMakesTheRequestNonEmpty() {
    assertFalse(
        new TenantUpdateRequest(null, null, null, null, null, null, null, null, null, true)
            .isEmpty());
  }

  @Test
  void changedFieldNamesRecordsAPasswordRotationWithoutThePassword() {
    // The audit trail is built from this, so the name must appear and the value
    // must not.
    final TenantUpdateRequest request =
        new TenantUpdateRequest(null, null, null, null, null, null, null, "s3cret", null, null);

    assertEquals(List.of("schemaPassword"), request.changedFieldNames());
    assertFalse(request.changedFieldNames().toString().contains("s3cret"));
  }

  @Test
  void changedFieldNamesListsEveryChangedFieldInAStableOrder() {
    final TenantUpdateRequest request =
        new TenantUpdateRequest(
            "n", "Asia/Kolkata", "d", "e@x.org", "host", "5432", "user", "pw", "params", true);

    assertEquals(
        List.of(
            "name",
            "timezoneId",
            "description",
            "contactEmail",
            "schemaServer",
            "schemaServerPort",
            "schemaUsername",
            "schemaPassword",
            "schemaConnectionParameters",
            "autoUpdate"),
        request.changedFieldNames());
  }
}
