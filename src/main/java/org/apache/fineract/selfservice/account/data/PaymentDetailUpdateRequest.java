package org.apache.fineract.selfservice.account.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO used to pass payment detail update instructions between Service and DAO layers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentDetailUpdateRequest {
    private Long paymentDetailId;
    private String routingCode;
}