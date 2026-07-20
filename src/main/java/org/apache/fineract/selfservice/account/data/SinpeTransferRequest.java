/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpeTransferRequest {
  private String originCustomerId;
  private String originCustomerName;
  private String originIban;
  private String destinationPhone;
  private BigDecimal amount;
  private String currencyCode;
  private String description;
  private Boolean debitIBAN;
  private List<CustomData> customData;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class CustomData {
    private String Name;
    private String Value;
  }
}
