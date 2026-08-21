/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import lombok.Data;

@Data
public class RemittancePayoutRequest {

  private String agent;
  private String clientIdMifos;
  private String reason;
  private String reasonCode;
  private String additionalInfoComment;
}
