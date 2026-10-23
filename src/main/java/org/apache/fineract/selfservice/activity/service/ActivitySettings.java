/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import java.util.Map;

/**
 * Immutable per-tenant activity log configuration (see {@link ActivitySettingsProvider}).
 *
 * <p>Parsing is lenient but safe: a missing, malformed or out-of-range value falls back to the
 * default, so a typo can never silently turn the retention into something absurd.
 */
public record ActivitySettings(boolean enabled, int retentionDays, int maxPageSize) {

  public static final String SERVICE_NAME = "SELF_SERVICE_ACTIVITY";

  static final int DEFAULT_RETENTION_DAYS = 90;
  static final int DEFAULT_MAX_PAGE_SIZE = 100;

  /** Used when nothing is configured: the history is kept for 90 days. */
  public static ActivitySettings defaults() {
    return fromProperties(Map.of());
  }

  public static ActivitySettings fromProperties(final Map<String, String> properties) {
    return new ActivitySettings(
        parseBoolean(properties.get("enabled"), true),
        parseInt(properties.get("retentionDays"), DEFAULT_RETENTION_DAYS, 1, 3650),
        parseInt(properties.get("maxPageSize"), DEFAULT_MAX_PAGE_SIZE, 10, 500));
  }

  private static boolean parseBoolean(final String raw, final boolean fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    return "true".equalsIgnoreCase(raw.trim());
  }

  private static int parseInt(final String raw, final int fallback, final int min, final int max) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      final int value = Integer.parseInt(raw.trim());
      return value < min || value > max ? fallback : value;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
