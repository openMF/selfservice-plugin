/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a
 * copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Immutable request object carrying every datum the fee-collection service needs.
 * Passed across the REQUIRES_NEW boundary so the callee never touches the
 * caller's JPA persistence context.
 *
 * <p>Multi-tenant safe: tenant routing is thread-bound, not object-bound.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeeCollectionRequest implements Serializable {

  private static final long serialVersionUID = 1L;

  /** PIN | SINPE_MOVIL | SAME_BANK */
  private String transferType;

  /** ISO-4217 code of the transfer (USD, CRC …) */
  private String currencyCode;

  /** INMEDIATA | DIFERIDA (used to look up the fee row) */
  private String transferMode;

  /** Principal amount of the original transfer */
  private BigDecimal transferAmount;

  /** IBAN / external-id / numeric id of the source savings account */
  private String fromAccount;

  /** PortfolioAccountType integer (2 = savings) */
  @Builder.Default private Integer fromAccountType = 2;

  /** Fineract client id that owns the source account */
  private Long clientId;

  /** Office that owns the client */
  private Long fromOfficeId;

  /** Pre-formatted date string accepted by Fineract (e.g. "26 July 2026") */
  private String transferDateForFineract;

  /** Fineract date-format pattern, e.g. "dd MMMM yyyy" */
  @Builder.Default private String dateFormat = "dd-MM-yyyy";

  /** Locale string, e.g. "en" */
  @Builder.Default private String locale = "en";

  /** Fee amount the client quoted (informational, may be null / 0) */
  private BigDecimal clientFeeAmount;
}