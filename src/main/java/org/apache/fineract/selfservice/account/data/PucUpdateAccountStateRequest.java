package org.apache.fineract.selfservice.account.data;

import lombok.Data;

@Data
public class PucUpdateAccountStateRequest {

    private String accountNumber;

    private String accountType;

    private String holderId;

    private String holderType;

    private String holderName;

    private String currencyCode;

    private String state;

    private String reason;

    private String ipNumber;
}
