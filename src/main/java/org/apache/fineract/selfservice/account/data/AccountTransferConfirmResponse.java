/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic envelope returned by the {@code /self/accounttransfers/confirm} endpoint. The
 * {@code transferType} discriminator tells the client which concrete shape {@code data} carries:
 *
 * <ul>
 *   <li>{@code SAME_BANK} → {@link SameBankTransferResponseData}
 *   <li>{@code PIN} → raw Map from the external PIN gateway
 *   <li>{@code SINPE_MOVIL} → raw Map from the SINPE gateway
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountTransferConfirmResponse {

    /** Transfer channel discriminator: SAME_BANK, PIN, SINPE_MOVIL, etc. */
    private String transferType;

    /** Channel-specific response payload. */
    private Object data;
}