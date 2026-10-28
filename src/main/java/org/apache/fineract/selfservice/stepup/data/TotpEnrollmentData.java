/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.data;

/**
 * What a client needs to pair an app: the {@code otpauth://} URI (to draw as a QR code) and the
 * same secret as Base32 text (to type in). Returned once; no call ever returns the secret again.
 */
public record TotpEnrollmentData(
    String secret, String otpauthUri, String issuer, int digits, int periodSeconds) {

  /** Never print the secret. */
  @Override
  public String toString() {
    return "TotpEnrollmentData[issuer=" + issuer + "]";
  }
}
