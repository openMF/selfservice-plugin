package org.apache.fineract.selfservice.account.data;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TptTransferRequest {
    private Long fromOfficeId;
    private Long fromClientId;
    private Integer fromAccountType;
    private String fromAccountId;
    private Long toOfficeId;
    private Long toClientId;
    private Integer toAccountType;
    private String toAccountId;
    private String transferDate;
    private BigDecimal transferAmount;
    private String transferDescription;
    private String dateFormat;
    private String locale;
}