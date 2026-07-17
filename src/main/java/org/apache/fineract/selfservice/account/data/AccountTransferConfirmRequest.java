package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AccountTransferConfirmRequest extends AccountTransferPrepareRequest {
  private String otp;
  private String transferId; // Used when validating the OTP

    private BigDecimal feeAmount;
    private BigDecimal totalAmount;
}
