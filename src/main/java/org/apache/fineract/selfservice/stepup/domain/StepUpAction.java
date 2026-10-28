/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import java.util.List;

/**
 * Sensitive operations that can be protected by step-up authentication.
 *
 * <p>Each action declares the ordered request fields that are bound into the payload hash. The same
 * field set is used when a client asks for a challenge and when the protected endpoint validates
 * the grant, so an OTP approved for one payload can never authorise another one.
 *
 * <p>A future {@code APPROVE_SECOND_SIGNATURE} action (US-E12-03) only needs a new constant here.
 */
public enum StepUpAction {
  /** Third-party transfer: {@code POST /v1/self/accounttransfers?type=tpt}. */
  TPT_TRANSFER(
      List.of(
          PayloadField.required("fromAccountId", FieldKind.ID),
          PayloadField.required("fromAccountType", FieldKind.ID),
          PayloadField.required("toAccountId", FieldKind.ID),
          PayloadField.required("toAccountType", FieldKind.ID),
          PayloadField.required("transferAmount", FieldKind.AMOUNT))),
  /** Transfer between own accounts: {@code POST /v1/self/accounttransfers}. */
  OWN_TRANSFER(
      List.of(
          PayloadField.required("fromAccountId", FieldKind.ID),
          PayloadField.required("fromAccountType", FieldKind.ID),
          PayloadField.required("toAccountId", FieldKind.ID),
          PayloadField.required("toAccountType", FieldKind.ID),
          PayloadField.required("transferAmount", FieldKind.AMOUNT))),
  /** Transfer confirmation: {@code POST /v1/self/accounttransfers/confirm}. */
  CONFIRM_TRANSFER(
      List.of(
          PayloadField.required("fromAccount", FieldKind.TEXT),
          PayloadField.optional("fromAccountType", FieldKind.ID),
          PayloadField.optional("toAccount", FieldKind.TEXT),
          PayloadField.optional("toAccountType", FieldKind.ID),
          PayloadField.optional("toPhoneNumber", FieldKind.TEXT),
          PayloadField.required("transferType", FieldKind.UPPER_TEXT),
          PayloadField.optional("currencyCode", FieldKind.UPPER_TEXT),
          PayloadField.required("transferAmount", FieldKind.AMOUNT))),
  /** Password change: {@code PUT /v1/self/user}. Bound to the action only (never the password). */
  CHANGE_PASSWORD(List.of()),
  /**
   * Pairing, re-issuing the recovery codes of, or removing the authenticator app: {@code POST
   * /v1/self/stepup/totp/enrollment/confirm}, {@code POST .../recovery-codes}, {@code DELETE
   * /v1/self/stepup/totp}. Bound to the action only.
   */
  MANAGE_AUTHENTICATOR(List.of());

  /** How a payload field is normalised before hashing. */
  public enum FieldKind {
    /** Free text; all whitespace removed. */
    TEXT,
    /** Free text; all whitespace removed and upper-cased. */
    UPPER_TEXT,
    /** Integer identifier; compared numerically. */
    ID,
    /** Monetary amount; compared numerically ignoring scale. */
    AMOUNT
  }

  /** One field of the canonical payload. */
  public record PayloadField(String name, FieldKind kind, boolean isRequired) {
    static PayloadField required(String name, FieldKind kind) {
      return new PayloadField(name, kind, true);
    }

    static PayloadField optional(String name, FieldKind kind) {
      return new PayloadField(name, kind, false);
    }
  }

  private final List<PayloadField> payloadFields;

  StepUpAction(List<PayloadField> payloadFields) {
    this.payloadFields = payloadFields;
  }

  /** Ordered fields bound into the payload hash (empty when only the action is bound). */
  public List<PayloadField> getPayloadFields() {
    return payloadFields;
  }
}
