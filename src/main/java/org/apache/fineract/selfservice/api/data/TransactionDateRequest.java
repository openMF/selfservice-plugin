/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.api.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Standardized date payload for transaction requests")
public class TransactionDateRequest {

    @Schema(description = "The transaction date string, e.g., '25-12-2023'", example = "25-12-2023")
    private String transactionDate;

    @Schema(description = "The date format used in the transactionDate field", example = "dd-MM-yyyy")
    private String dateFormat;

    @Schema(description = "The locale for date parsing", example = "en")
    private String locale;
}