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
 * Result returned by {@link org.apache.fineract.selfservice.account.service.SelfServiceFeeCollectionService}.
 * The caller inspects this to decide logging / audit without sharing a persistence context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeeCollectionResult implements Serializable {

  private static final long serialVersionUID = 1L;

  public enum Status {
    COMPLETED,
    FAILED,
    SKIPPED,
    DISABLED
  }

  private boolean successful;
  private Status status;

  /** Fineract savings-account transaction id of the commission debit (null when skipped) */
  private Long transactionId;

  /** Final fee amount actually charged (0 when skipped / waived) */
  private BigDecimal feeAmount;

  /** ISO-4217 currency of the fee */
  private String currency;

  /** Human-readable detail (e.g. "Exento: Dentro del umbral diario SINPE Móvil") */
  private String message;
}