/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.data;

import java.util.List;

/**
 * The recovery codes of a user, in the form to show ({@code ABCDE-FGHJK}). Returned once, when they
 * are created: only a keyed hash is kept.
 */
public record TotpRecoveryCodesData(List<String> recoveryCodes) {

  /** Never print the codes. */
  @Override
  public String toString() {
    return "TotpRecoveryCodesData[count=" + recoveryCodes.size() + "]";
  }
}
