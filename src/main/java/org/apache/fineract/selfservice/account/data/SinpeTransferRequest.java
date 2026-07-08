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
