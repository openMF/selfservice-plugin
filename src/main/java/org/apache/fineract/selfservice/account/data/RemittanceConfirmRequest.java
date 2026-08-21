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
public class RemittanceConfirmRequest {

  private String vendor;
  private String operationType;
  private Long savingsAccountId;
  private String quoteId; // if prepare returned one
  private String countryFrom;
  private String countryTo;
  private String deliveryMethod;
  private String productId;
  private BigDecimal amount;
  private String currency;
  private String amountType;
  private String senderFirstName;
  private String senderLastName;
  private String senderMiddleName;
  private String senderDateOfBirth;
  private String senderNationality;
  private String senderDocumentType;
  private String senderDocumentNumber;
  private String senderAddress;
  private String senderCity;
  private String senderState;
  private String senderZipCode;
  private String senderCountry;
  private String senderPhone;
  private String senderOccupation;
  private String recipientFirstName;
  private String recipientLastName;
  private String recipientMiddleName;
  private String recipientMotherMaidenName;
  private String recipientDateOfBirth;
  private String recipientNationality;
  private String recipientCity;
  private String recipientState;
  private String recipientCountry;
  private String recipientAddress;
  private String recipientZipCode;
  private String recipientPhone;
  private String recipientEmail;
  private String recipientDocumentType;
  private String recipientDocumentNumber;
  private String bankAccountNo;
  private String bankRoutingCode;
  private String bankId;
  private String payoutPartnerId;
  private String payoutLocationId;
  private String transferReason;
  private String additionalInfo;
}
