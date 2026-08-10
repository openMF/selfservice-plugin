/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nested custom data block returned inside the SAME_BANK transfer confirmation response. Mirrors
 * the key/value pairs that the core-banking switch expects for reconciliation and receipt
 * generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SameBankTransferCustomData {

  /** Total amount debited (transfer + fee), as a plain string for display purposes. */
  private String totalAmount;

  /** Free-text description supplied by the customer. */
  private String transferDescription;

  /** Commission / fee charged for the transfer, as a plain string. */
  private String feeAmount;

  /** Net debit amount (transfer amount only), as a plain string. */
  private String debitAmount;

  /** Exchange rate applied. Always "1" for same-bank, same-currency transfers. */
  private String exchangeRateAmount;
  
  private String fromAccountIdentifier;
    private String toAccountIdentifier;
    private String reference;
    private Map<String, Object> destinationCustomer;
}
