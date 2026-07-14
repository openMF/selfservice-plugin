package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class AccountTransferPrepareRequest {
  private String fromAccountId;
  private Integer fromAccountType;
  private String toAccountId;
  private Integer toAccountType;
  private String toPhoneNumber; // Used for SINPE Móvil
  private BigDecimal transferAmount;
  private String transferDate;
  private String transferDescription;
  private String transferType; // PIN, SINPE_MOVIL, LINK_PAGO
  private String transferMode; // INMEDIATA, T_PLUS_1
  private String currencyCode; // CRC, USD - ADDED THIS FIELD
  private String institutionAccountId;
}
