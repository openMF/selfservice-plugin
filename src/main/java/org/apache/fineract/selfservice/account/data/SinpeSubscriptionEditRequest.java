package org.apache.fineract.selfservice.account.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SinpeSubscriptionEditRequest {
  private String phoneNumber;
  private SinpeSubscriptionEditData dataToEdit;
}
