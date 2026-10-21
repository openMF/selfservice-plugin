/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.data;

/**
 * Response of a successful verification: the one-time grant to send as {@code X-Step-Up-Token} on
 * the protected request. This is the only moment the token is ever visible; the server keeps just
 * its SHA-256.
 */
public record StepUpGrantData(String stepUpToken, String action, String expiresAt) {

  /** Never print the token. */
  @Override
  public String toString() {
    return "StepUpGrantData[action=" + action + ", expiresAt=" + expiresAt + "]";
  }
}
