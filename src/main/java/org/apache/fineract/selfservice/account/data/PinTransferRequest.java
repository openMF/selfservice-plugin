package org.apache.fineract.selfservice.account.data;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PinTransferRequest {
    private BigDecimal amount;
    private String currency;
    private String description;
    private String originCustomerId;
    private String originIdType;
    private String destinationEmail;
    private String originCustomerName;
    private String originIban;
    private String destinationCustomerId;
    private String destinationIdType;
    private String destinationCustomerName;
    private String destinationIban;
    private String originEmail;
    private String branchName;
    private String reference;
    private Boolean debitIban;
}