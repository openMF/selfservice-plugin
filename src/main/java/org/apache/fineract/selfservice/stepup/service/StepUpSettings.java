/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;

/**
 * Immutable per-tenant step-up configuration (see {@link StepUpSettingsProvider}).
 *
 * <p>Parsing is lenient but safe: a missing, malformed or out-of-range value falls back to the
 * default, so a typo can never silently weaken a limit below the allowed bounds. {@code enabled}
 * defaults to {@code false}, which keeps existing deployments behaving exactly as before.
 */
public record StepUpSettings(
    boolean enabled,
    int otpLength,
    int otpTtlSeconds,
    int maxAttempts,
    int lockoutSeconds,
    int resendCooldownSeconds,
    int maxResends,
    int grantTtlSeconds,
    Set<StepUpAction> protectedActions,
    Set<StepUpChannel> channels,
    int challengeRateLimit,
    int challengeRateWindowSeconds,
    int retentionDays,
    boolean invalidateSessionsOnPasswordChange,
    String hmacSecret) {

  public static final String SERVICE_NAME = "SELF_SERVICE_STEP_UP";

  /** Tenant property that holds the key used to hash the OTPs of this tenant. */
  public static final String HMAC_SECRET_PROPERTY = "hmacSecret";

  static final Set<StepUpAction> DEFAULT_PROTECTED_ACTIONS =
      EnumSet.of(
          StepUpAction.TPT_TRANSFER, StepUpAction.CONFIRM_TRANSFER, StepUpAction.CHANGE_PASSWORD);

  /** Defaults used when nothing is configured: step-up is OFF. */
  public static StepUpSettings defaults() {
    return fromProperties(Map.of());
  }

  /** {@code true} if step-up is on AND this action is in the protected list. */
  public boolean isProtected(final StepUpAction action) {
    return enabled && protectedActions.contains(action);
  }

  public boolean isChannelAllowed(final StepUpChannel channel) {
    return channels.contains(channel);
  }

  /** Builds settings from the raw key/value properties of the external service. */
  public static StepUpSettings fromProperties(final Map<String, String> properties) {
    final boolean enabled = parseBoolean(properties.get("enabled"), false);
    return new StepUpSettings(
        enabled,
        parseInt(properties.get("otpLength"), 6, 4, 8),
        parseInt(properties.get("otpTtlSeconds"), 300, 60, 900),
        parseInt(properties.get("maxAttempts"), 5, 1, 10),
        parseInt(properties.get("lockoutSeconds"), 900, 60, 86_400),
        parseInt(properties.get("resendCooldownSeconds"), 60, 10, 600),
        parseInt(properties.get("maxResends"), 3, 0, 10),
        parseInt(properties.get("grantTtlSeconds"), 120, 30, 600),
        parseActions(properties.get("protectedActions")),
        parseChannels(properties.get("channels")),
        parseInt(properties.get("challengeRateLimit"), 5, 1, 50),
        parseInt(properties.get("challengeRateWindowSeconds"), 900, 60, 86_400),
        parseInt(properties.get("retentionDays"), 30, 1, 365),
        parseBoolean(properties.get("invalidateSessionsOnPasswordChange"), enabled),
        blankToNull(properties.get(HMAC_SECRET_PROPERTY)));
  }

  private static String blankToNull(final String raw) {
    return raw == null || raw.isBlank() ? null : raw;
  }

  /** Never prints the HMAC secret. */
  @Override
  public String toString() {
    return "StepUpSettings[enabled="
        + enabled
        + ", otpLength="
        + otpLength
        + ", protectedActions="
        + protectedActions
        + ", channels="
        + channels
        + ", hmacSecret="
        + (hmacSecret == null ? "not set" : "set")
        + "]";
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

  private static Set<StepUpAction> parseActions(final String raw) {
    if (raw == null) {
      return EnumSet.copyOf(DEFAULT_PROTECTED_ACTIONS);
    }
    final Set<StepUpAction> result = EnumSet.noneOf(StepUpAction.class);
    Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .forEach(
            name -> {
              try {
                result.add(StepUpAction.valueOf(name.toUpperCase(Locale.ROOT)));
              } catch (IllegalArgumentException ignored) {
                // unknown action names are ignored
              }
            });
    return result;
  }

  private static Set<StepUpChannel> parseChannels(final String raw) {
    if (raw == null || raw.isBlank()) {
      return EnumSet.allOf(StepUpChannel.class);
    }
    final Set<StepUpChannel> result = EnumSet.noneOf(StepUpChannel.class);
    Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .forEach(
            name -> {
              try {
                result.add(StepUpChannel.valueOf(name.toUpperCase(Locale.ROOT)));
              } catch (IllegalArgumentException ignored) {
                // unknown channel names are ignored
              }
            });
    return result;
  }
}
