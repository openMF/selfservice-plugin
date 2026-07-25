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
@Table(name = "m_selfservice_payment_link")
@Getter
@Setter
@NoArgsConstructor
public class SelfServicePaymentLink {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_selfservice_user_id", nullable = false)
  private Long appSelfServiceUserId;

  @Column(name = "client_id", nullable = false)
  private Long clientId;

  @Column(name = "savings_account_id", nullable = false)
  private Long savingsAccountId;

  @Column(name = "checkout_id", nullable = false, length = 128)
  private String checkoutId;

  @Column(name = "payment_url", length = 512)
  private String paymentUrl;

  @Column(name = "payment_status", nullable = false, length = 32)
  private String paymentStatus;

  @Column(name = "customer_name", length = 200)
  private String customerName;

  @Column(name = "customer_email", length = 100)
  private String customerEmail;

  @Column(name = "customer_phone", length = 30)
  private String customerPhone;

  @Column(name = "amount", nullable = false, precision = 19, scale = 6)
  private BigDecimal amount;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "success")
  private boolean success;

  @Column(name = "created_on", nullable = false)
  private LocalDateTime createdOn = LocalDateTime.now();

  @Column(name = "external_response", columnDefinition = "TEXT")
  private String externalResponse;
}
