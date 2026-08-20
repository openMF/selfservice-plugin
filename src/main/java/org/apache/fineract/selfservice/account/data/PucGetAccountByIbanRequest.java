package org.apache.fineract.selfservice.account.data;

import lombok.Data;

@Data
public class PucGetAccountByIbanRequest {

    private String accountNumber;

    private String ipNumber;
}
