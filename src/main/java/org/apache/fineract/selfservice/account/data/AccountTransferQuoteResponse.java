package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountTransferQuoteResponse {
  private BigDecimal feeAmount;
  private BigDecimal totalAmount;
  private String currencyCode;
  private String feeDescription;
}
