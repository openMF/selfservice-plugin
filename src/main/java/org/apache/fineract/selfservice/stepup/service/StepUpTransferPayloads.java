/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the payload field values of a transfer from the REAL request, in the exact shape that
 * {@link StepUpPayloadHasher} expects. Keeping the mapping in one place guarantees that what the
 * user approved when asking for a challenge and what the protected endpoint receives are compared
 * field by field.
 */
public final class StepUpTransferPayloads {

  private StepUpTransferPayloads() {}

  /**
   * {@code TPT_TRANSFER} / {@code OWN_TRANSFER} ({@code POST /accounttransfers}): the accounts and
   * the amount as validated by the transfer validator.
   */
  public static Map<String, String> forTransfer(
      final Object fromAccountId,
      final Object fromAccountType,
      final Object toAccountId,
      final Object toAccountType,
      final BigDecimal amount) {
    final Map<String, String> values = new HashMap<>();
    put(values, "fromAccountId", fromAccountId);
    put(values, "fromAccountType", fromAccountType);
    put(values, "toAccountId", toAccountId);
    put(values, "toAccountType", toAccountType);
    put(values, "transferAmount", amount == null ? null : amount.toPlainString());
    return values;
  }

  /** {@code CONFIRM_TRANSFER} ({@code POST /accounttransfers/confirm}). */
  public static Map<String, String> forConfirm(
      final String fromAccount,
      final Integer fromAccountType,
      final String toAccount,
      final Integer toAccountType,
      final String toPhoneNumber,
      final String transferType,
      final String currencyCode,
      final BigDecimal amount) {
    final Map<String, String> values = new HashMap<>();
    put(values, "fromAccount", fromAccount);
    put(values, "fromAccountType", fromAccountType);
    put(values, "toAccount", toAccount);
    put(values, "toAccountType", toAccountType);
    put(values, "toPhoneNumber", toPhoneNumber);
    put(values, "transferType", transferType);
    put(values, "currencyCode", currencyCode);
    put(values, "transferAmount", amount == null ? null : amount.toPlainString());
    return values;
  }

  private static void put(final Map<String, String> values, final String key, final Object value) {
    if (value != null) {
      values.put(key, String.valueOf(value));
    }
  }
}
