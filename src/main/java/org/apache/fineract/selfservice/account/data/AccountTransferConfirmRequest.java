package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AccountTransferConfirmRequest extends AccountTransferPrepareRequest {
  private String otp;
  private String transferId; // Used when validating the OTP
  
  private String fromAccountId;
    private Integer fromAccountType;
    private String toAccountId;
    private Integer toAccountType;
    private BigDecimal transferAmount;
    private String transferDate;
    private String transferDescription;
    private String transferType;
    private String transferMode;
    private String currencyCode;
    private BigDecimal feeAmount;
    private BigDecimal totalAmount;
    private String institutionAccountId;
}
