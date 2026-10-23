/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import com.google.gson.Gson;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.fineract.selfservice.activity.domain.ActivityType;

/**
 * Builds the {@code details} JSON of an activity from a <b>whitelist per activity type</b>. The raw
 * map that callers pass (for example the {@code contextData} of a notification event, which holds
 * e-mail addresses, phone numbers and one-time codes) is never copied as a whole: only the listed
 * keys are read, validated, shortened and renamed.
 */
public final class ActivityDetails {

  private static final Gson GSON = new Gson();
  private static final int MAX_TEXT = 100;
  private static final Pattern AMOUNT = Pattern.compile("\\d{1,15}([.,]\\d{1,6})?");
  private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");
  private static final Pattern DIGITS = Pattern.compile("\\d{1,19}");
  private static final Pattern ACTION = Pattern.compile("created|updated|deleted");
  private static final Pattern ACTION_CODE = Pattern.compile("[A-Z][A-Z_]{2,39}");

  /** The input keys each type may read. */
  private static final Map<ActivityType, List<String>> ALLOWED = new EnumMap<>(ActivityType.class);

  static {
    ALLOWED.put(
        ActivityType.TRANSFER_CONFIRMED,
        List.of(
            "transactionAmount",
            "currencyCode",
            "currency",
            "toClientName",
            "toAccountNumber",
            "transferId"));
    for (final ActivityType type :
        List.of(
            ActivityType.BENEFICIARY_ADDED,
            ActivityType.BENEFICIARY_UPDATED,
            ActivityType.BENEFICIARY_DELETED)) {
      ALLOWED.put(type, List.of("beneficiaryName", "beneficiaryId"));
    }
    ALLOWED.put(ActivityType.SINPE_SUBSCRIPTION_CHANGED, List.of("action"));
    for (final ActivityType type :
        List.of(
            ActivityType.STEP_UP_REQUESTED,
            ActivityType.STEP_UP_VERIFIED,
            ActivityType.STEP_UP_LOCKED)) {
      ALLOWED.put(type, List.of("action"));
    }
  }

  private ActivityDetails() {}

  /** The input keys {@code type} may read (empty for types that carry no details). */
  public static List<String> allowedKeys(final ActivityType type) {
    return ALLOWED.getOrDefault(type, List.of());
  }

  /** The JSON text to store, or {@code null} when there is nothing to keep. */
  public static String toJson(final ActivityType type, final Map<String, Object> source) {
    if (source == null || source.isEmpty() || !ALLOWED.containsKey(type)) {
      return null;
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    switch (type) {
      case TRANSFER_CONFIRMED -> {
        put(out, "amount", amount(source.get("transactionAmount")));
        put(out, "currency", currency(first(source, "currencyCode", "currency")));
        put(out, "toName", text(source.get("toClientName")));
        put(out, "toAccountLast4", last4(source.get("toAccountNumber")));
        put(out, "transferId", digits(source.get("transferId")));
      }
      case BENEFICIARY_ADDED, BENEFICIARY_UPDATED, BENEFICIARY_DELETED -> {
        put(out, "name", text(source.get("beneficiaryName")));
        put(out, "beneficiaryId", digits(source.get("beneficiaryId")));
      }
      case SINPE_SUBSCRIPTION_CHANGED -> put(out, "action", action(source.get("action")));
      case STEP_UP_REQUESTED, STEP_UP_VERIFIED, STEP_UP_LOCKED ->
          put(out, "action", actionCode(source.get("action")));
      default -> {
        // no details for the other types
      }
    }
    return out.isEmpty() ? null : GSON.toJson(out);
  }

  private static void put(final Map<String, Object> out, final String key, final String value) {
    if (value != null) {
      out.put(key, value);
    }
  }

  private static Object first(final Map<String, Object> source, final String... keys) {
    for (final String key : keys) {
      if (source.get(key) != null) {
        return source.get(key);
      }
    }
    return null;
  }

  private static String amount(final Object raw) {
    final String value = raw == null ? "" : raw.toString().trim();
    return AMOUNT.matcher(value).matches() ? value.replace(',', '.') : null;
  }

  private static String currency(final Object raw) {
    final String value = raw == null ? "" : raw.toString().trim();
    return CURRENCY.matcher(value).matches() ? value.toUpperCase(java.util.Locale.ROOT) : null;
  }

  private static String digits(final Object raw) {
    final String value = raw == null ? "" : raw.toString().trim();
    return DIGITS.matcher(value).matches() ? value : null;
  }

  private static String action(final Object raw) {
    final String value =
        raw == null ? "" : raw.toString().trim().toLowerCase(java.util.Locale.ROOT);
    return ACTION.matcher(value).matches() ? value : null;
  }

  /** The protected action a step-up was for, for example {@code TPT_TRANSFER}. */
  private static String actionCode(final Object raw) {
    final String value = raw == null ? "" : raw.toString().trim();
    return ACTION_CODE.matcher(value).matches() ? value : null;
  }

  /** Only the last four digits of an account number or phone number, never the whole value. */
  private static String last4(final Object raw) {
    if (raw == null) {
      return null;
    }
    final String onlyDigits = raw.toString().replaceAll("\\D", "");
    if (onlyDigits.length() < 4) {
      return null;
    }
    return onlyDigits.substring(onlyDigits.length() - 4);
  }

  /**
   * A display name: control characters removed, shortened, "N/A"/"Unknown" placeholders dropped.
   */
  private static String text(final Object raw) {
    if (raw == null) {
      return null;
    }
    final String value = raw.toString().replaceAll("\\p{Cntrl}", " ").trim();
    if (value.isEmpty()
        || "N/A".equalsIgnoreCase(value)
        || "Unknown".equalsIgnoreCase(value)
        || "null".equalsIgnoreCase(value)) {
      return null;
    }
    return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
  }
}
