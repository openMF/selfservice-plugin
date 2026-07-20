/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class AccountTransferPrepareRequest {
  private String fromAccount;
  private Integer fromAccountType;
  private String toAccount;
  private Integer toAccountType;
  private String toPhoneNumber; // Used for SINPE Móvil
  private BigDecimal transferAmount;
  private String transferDate;
  private String transferDescription;
  private String transferType; // SAME_BANK, PIN, SINPE_MOVIL, LINK_PAGO
  private String transferMode; // INSTANT, T_PLUS_1
  private String currencyCode; // CRC, USD
  private String institutionAccountId;
  private String reference; // (i.e: "Factura-001")
  private String locale; // (i.e: "es")
  private String dateFormat;
}
