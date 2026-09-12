/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantStatusTest {

  private final Locale originalLocale = Locale.getDefault();

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(originalLocale);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "active", "Active", "  active  "})
  void fromString_acceptsAnyCaseAndSurroundingWhitespace(final String value) {
    assertEquals(Optional.of(TenantStatus.ACTIVE), TenantStatus.fromString(value));
  }

  @Test
  void fromString_isNotAffectedByTheDefaultLocale() {
    // Turkish folds a dotted I to a dotless i. If the parser used the default
    // locale, INACTIVE would stop being recognised on a Turkish server - the
    // same class of bug the branding module documents for colour names.
    Locale.setDefault(Locale.forLanguageTag("tr"));

    assertEquals(Optional.of(TenantStatus.INACTIVE), TenantStatus.fromString("INACTIVE"));
    assertEquals(Optional.of(TenantStatus.INACTIVE), TenantStatus.fromString("inactive"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "UNKNOWN", "ACTIVE_", "deleted"})
  void fromString_rejectsAnythingThatIsNotAStatus(final String value) {
    assertTrue(TenantStatus.fromString(value).isEmpty());
  }

  @Test
  void fromString_handlesNull() {
    assertTrue(TenantStatus.fromString(null).isEmpty());
  }

  @Test
  void names_listsEveryStatusInDeclarationOrder() {
    assertEquals(java.util.List.of("ACTIVE", "INACTIVE", "SUSPENDED"), TenantStatus.names());
  }
}
