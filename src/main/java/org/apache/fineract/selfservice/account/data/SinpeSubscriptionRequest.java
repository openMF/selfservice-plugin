package org.apache.fineract.selfservice.account.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpeSubscriptionRequest {
  private String phoneNumber;
  private String customerName;
  private String customerId;
  private String customerEmail;
  private String notificationType;
  private String iban;
  private String currencyCode;
  private Integer dailyMaxAmountNc;
  private Integer monthlyMaxAmountNc;
  private Integer dailyMaxAmountNotAuth;
  private Integer monthlyMaxAmountNotAuth;
  private Integer dailyMaxAmountIncoming;
  private Integer monthlyMaxAmountIncoming;
  private Boolean overwriteAmounts;
  private String token;
}
