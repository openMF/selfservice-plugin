/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction.FieldKind;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction.PayloadField;

/**
 * Builds the canonical form of an action payload and its SHA-256 digest.
 *
 * <p>The canonical string is {@code v1|<ACTION>|<field>=<normalised value>|...} over the ordered
 * fields declared by {@link StepUpAction}. Values are normalised so that harmless formatting
 * differences (whitespace, case of codes, {@code 100} vs {@code 100.00}) do not change the digest,
 * while any change of account, destination or amount does.
 *
 * <p>The same function is used when a challenge is created (from the payload the client sends) and
 * when a protected endpoint consumes a grant (from the real request), so the server never trusts a
 * client-computed hash.
 */
public final class StepUpPayloadHasher {

  private static final String VERSION = "v1";

  private StepUpPayloadHasher() {}

  /** Lower-case hex SHA-256 of the canonical payload (64 chars). */
  public static String hash(final StepUpAction action, final Map<String, String> values) {
    return StepUpCrypto.sha256Hex(canonicalize(action, values));
  }

  /** The canonical string that is hashed (exposed for tests; never log it in production). */
  static String canonicalize(final StepUpAction action, final Map<String, String> values) {
    final StringBuilder canonical = new StringBuilder(VERSION).append('|').append(action.name());
    for (final PayloadField field : action.getPayloadFields()) {
      canonical
          .append('|')
          .append(field.name())
          .append('=')
          .append(escape(normalise(field.kind(), values.get(field.name()))));
    }
    return canonical.toString();
  }

  /** Names of required fields that are missing or blank. */
  public static List<String> missingRequiredFields(
      final StepUpAction action, final Map<String, String> values) {
    final List<String> missing = new ArrayList<>();
    for (final PayloadField field : action.getPayloadFields()) {
      if (field.isRequired()) {
        final String value = values.get(field.name());
        if (value == null || value.isBlank()) {
          missing.add(field.name());
        }
      }
    }
    return missing;
  }

  /**
   * Extracts the action's payload fields from a JSON object. Only primitive values are accepted;
   * anything else is kept as its JSON text so it can never hash like a valid value.
   */
  public static Map<String, String> valuesFromJson(
      final StepUpAction action, final JsonObject payload) {
    final Map<String, String> values = new HashMap<>();
    if (payload == null) {
      return values;
    }
    for (final PayloadField field : action.getPayloadFields()) {
      final JsonElement element = payload.get(field.name());
      if (element == null || element.isJsonNull()) {
        continue;
      }
      values.put(
          field.name(), element.isJsonPrimitive() ? element.getAsString() : element.toString());
    }
    return values;
  }

  private static String normalise(final FieldKind kind, final String raw) {
    if (raw == null) {
      return "";
    }
    return switch (kind) {
      case TEXT -> stripWhitespace(raw);
      case UPPER_TEXT -> stripWhitespace(raw).toUpperCase(Locale.ROOT);
      case ID -> normaliseId(raw);
      case AMOUNT -> normaliseAmount(raw);
    };
  }

  /**
   * Escapes the canonical-form delimiters so a value can never be mistaken for the boundary of
   * another field (field-injection / ambiguity attack).
   */
  private static String escape(final String value) {
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("=", "\\=");
  }

  private static String stripWhitespace(final String raw) {
    return raw.replaceAll("\\s+", "");
  }

  private static String normaliseId(final String raw) {
    final String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    try {
      return new BigDecimal(trimmed).toBigIntegerExact().toString();
    } catch (NumberFormatException | ArithmeticException e) {
      return trimmed;
    }
  }

  private static String normaliseAmount(final String raw) {
    final String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    try {
      final BigDecimal amount = new BigDecimal(trimmed).stripTrailingZeros();
      return amount.signum() == 0 ? "0" : amount.toPlainString();
    } catch (NumberFormatException e) {
      return trimmed;
    }
  }
}
