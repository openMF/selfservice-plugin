/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RemittanceResponse {

  private Long id;
  private String vendor;
  private String operationType;
  private String externalId;
  private String pin;
  private String referenceNumber;
  private String status;
  private String senderName;
  private String recipientName;
  private BigDecimal receivingAmount;
  private String receivingCurrency;
  private BigDecimal sendingAmount;
  private String sendingCurrency;
  private BigDecimal feeAmount;
  private String feeCurrency;
  private String countryFrom;
  private String countryTo;
  private String deliveryMethod;
  private LocalDateTime createdOn;
  private String message;
}
