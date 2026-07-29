/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class PaymentLinkConfirmRequest {
    private String clientAccount;
    private BigDecimal amount;   // Required to calculate the fee dynamically 
    private String currency;     // Required to calculate the fee dynamically
    private String transferType; // Required to calculate the fee dynamically
    private String transferMode; // Required to calculate the fee dynamically
    private String description;
    
    // Payer details for the external payment gateway
    private String payerName;
    private String payerEmail;
    private String payerPhone;
}