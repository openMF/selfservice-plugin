package org.apache.fineract.selfservice.account.data;

import lombok.Data;

@Data
public class PucAddAccountRequest {

    private String accountNumber;

    private String accountType;

    private String holderId;

    private String holderIdType;

    private String holder;

    private String currencyCode;

    private String ipNumber;
}
