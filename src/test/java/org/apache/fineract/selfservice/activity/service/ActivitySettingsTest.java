/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ActivitySettingsTest {

  @Test
  void defaults_keepNinetyDaysAndAreOn() {
    final ActivitySettings settings = ActivitySettings.defaults();

    assertTrue(settings.enabled());
    assertEquals(90, settings.retentionDays());
    assertEquals(100, settings.maxPageSize());
  }

  @Test
  void aTenantCanChooseALongerRetention() {
    final ActivitySettings settings =
        ActivitySettings.fromProperties(
            Map.of("retentionDays", "365", "maxPageSize", "50", "enabled", "false"));

    assertEquals(365, settings.retentionDays());
    assertEquals(50, settings.maxPageSize());
    assertFalse(settings.enabled());
  }

  @Test
  void theBoundsOfTheRetentionAreAccepted() {
    assertEquals(1, ActivitySettings.fromProperties(Map.of("retentionDays", "1")).retentionDays());
    assertEquals(
        3650, ActivitySettings.fromProperties(Map.of("retentionDays", "3650")).retentionDays());
  }

  @Test
  void valuesOutsideTheRangeOrMalformed_fallBackToTheDefaults() {
    for (final String bad : new String[] {"0", "-5", "3651", "abc", "", " ", "9999999999", "1.5"}) {
      assertEquals(
          90,
          ActivitySettings.fromProperties(Map.of("retentionDays", bad)).retentionDays(),
          "retentionDays=" + bad);
    }
    assertEquals(100, ActivitySettings.fromProperties(Map.of("maxPageSize", "5")).maxPageSize());
    assertEquals(100, ActivitySettings.fromProperties(Map.of("maxPageSize", "501")).maxPageSize());
  }

  @Test
  void enabledOnlyTurnsOffWithSomethingOtherThanTrue_butABlankKeepsTheDefault() {
    assertTrue(ActivitySettings.fromProperties(Map.of("enabled", "TRUE")).enabled());
    assertTrue(ActivitySettings.fromProperties(Map.of("enabled", " ")).enabled());
    assertFalse(ActivitySettings.fromProperties(Map.of("enabled", "no")).enabled());
  }
}
