/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PaymentLinkRequest {

  private String payerName;
  private String payerEmail;
  private String payerPhone;
  private String clientAccount; // savings external id
  private BigDecimal amount;
  private BigDecimal feeAmount;
  private String currency;
  private String currencyFee;
  private String description;
}
