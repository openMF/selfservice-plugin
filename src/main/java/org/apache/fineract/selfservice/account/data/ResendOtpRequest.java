/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import lombok.Data;

@Data
public class ResendOtpRequest {
  // Optional context for resend (e.g., to match transfer session)
  private String fromAccount;
  private String toAccount;
  private String transferType; // e.g., SAME_BANK, SINPE_MOVIL, etc.
  // Can be extended with transferId if needed for future tracking
  private String transferDescription; // optional for context in notification
}
