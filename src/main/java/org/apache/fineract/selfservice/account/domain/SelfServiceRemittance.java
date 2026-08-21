/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "m_selfservice_remittance")
@Getter
@Setter
@NoArgsConstructor
public class SelfServiceRemittance {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_selfservice_user_id", nullable = false)
  private Long appSelfServiceUserId;

  @Column(name = "client_id", nullable = false)
  private Long clientId;

  @Column(name = "savings_account_id")
  private Long savingsAccountId;

  @Column(name = "vendor", nullable = false, length = 32)
  private String vendor;

  @Column(name = "operation_type", nullable = false, length = 16)
  private String operationType; // SEND or PAYOUT

  @Column(name = "external_id", length = 128)
  private String externalId;

  @Column(name = "pin", length = 64)
  private String pin;

  @Column(name = "reference_number", length = 128)
  private String referenceNumber;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "sender_name", length = 200)
  private String senderName;

  @Column(name = "recipient_name", length = 200)
  private String recipientName;

  @Column(name = "receiving_amount", precision = 19, scale = 6)
  private BigDecimal receivingAmount;

  @Column(name = "receiving_currency", length = 3)
  private String receivingCurrency;

  @Column(name = "sending_amount", precision = 19, scale = 6)
  private BigDecimal sendingAmount;

  @Column(name = "sending_currency", length = 3)
  private String sendingCurrency;

  @Column(name = "fee_amount", precision = 19, scale = 6)
  private BigDecimal feeAmount;

  @Column(name = "fee_currency", length = 3)
  private String feeCurrency;

  @Column(name = "country_from", length = 3)
  private String countryFrom;

  @Column(name = "country_to", length = 3)
  private String countryTo;

  @Column(name = "delivery_method", length = 64)
  private String deliveryMethod;

  @Column(name = "product_id", length = 32)
  private String productId;

  @Column(name = "created_on", nullable = false)
  private LocalDateTime createdOn = LocalDateTime.now();

  @Column(name = "updated_on")
  private LocalDateTime updatedOn;

  @Column(name = "external_response", columnDefinition = "TEXT")
  private String externalResponse;

  @Column(name = "additional_info", columnDefinition = "TEXT")
  private String additionalInfo;
}
