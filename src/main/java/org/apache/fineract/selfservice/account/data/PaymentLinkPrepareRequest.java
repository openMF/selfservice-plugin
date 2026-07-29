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
public class PaymentLinkPrepareRequest {
    private String clientAccount;    // Savings account external ID or internal ID
    private BigDecimal amount;
    private String currency;         // e.g., "CRC", "USD"
    private String transferType;     // e.g., "PAYMENT_LINK"
    private String transferMode;     // e.g., "INSTANT"
    private String description;
}