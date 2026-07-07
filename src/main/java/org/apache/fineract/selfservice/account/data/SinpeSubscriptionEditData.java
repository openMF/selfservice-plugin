package org.apache.fineract.selfservice.account.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpeSubscriptionEditData {
  private String customerEmail;
  private String notificationType;
  private String iban;
  private Integer dailyMaxAmountNc;
  private Integer monthlyMaxAmountNc;
  private Integer dailyMaxAmountNotAuth;
  private Integer monthlyMaxAmountNotAuth;
  private Integer dailyMaxAmountIncoming;
  private Integer monthlyMaxAmountIncoming;
}
