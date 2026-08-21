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
public class RemittancePrepareRequest {

  private String vendor; // RIA, TRANZMIT etc
  private String operationType; // SEND or PAYOUT
  private Long savingsAccountId;
  private String countryFrom;
  private String countryTo;
  private String deliveryMethod;
  private String productId;
  private BigDecimal amount;
  private String currency;
  private String amountType; // SENDING or RECEIVING
  private String senderFirstName;
  private String senderLastName;
  private String recipientFirstName;
  private String recipientLastName;
  private String recipientCity;
  private String recipientState;
  private String recipientCountry;
  private String recipientAddress;
  private String recipientPhone;
  private String recipientDocumentType;
  private String recipientDocumentNumber;
  private String bankAccountNo;
  private String bankId;
  private String payoutPartnerId;
  private String transferReason;
}
