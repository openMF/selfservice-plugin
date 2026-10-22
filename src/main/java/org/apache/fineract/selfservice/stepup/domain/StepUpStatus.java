/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

/** Lifecycle of a step-up challenge. */
public enum StepUpStatus {
  /** OTP issued, waiting for verification. */
  PENDING,
  /** OTP verified; a one-time grant token was issued and not yet used. */
  VERIFIED,
  /** The grant was used by the protected operation. Terminal. */
  CONSUMED,
  /** The OTP expired before it was verified. Terminal. */
  EXPIRED,
  /** Too many wrong attempts. Terminal. */
  LOCKED,
  /** Replaced by a newer challenge for the same user and action. Terminal. */
  SUPERSEDED
}
