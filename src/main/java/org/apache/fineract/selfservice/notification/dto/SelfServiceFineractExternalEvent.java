/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class SelfServiceFineractExternalEvent {
    private String type;
    private String category;
    private String createdAt;
    private JsonNode payload;
}