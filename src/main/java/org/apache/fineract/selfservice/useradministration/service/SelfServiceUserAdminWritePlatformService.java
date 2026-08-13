/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.service;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

public interface SelfServiceUserAdminWritePlatformService {

  CommandProcessingResult activate(Long userId);

  CommandProcessingResult inactivate(Long userId);

  CommandProcessingResult linkClient(Long userId, Long clientId);

  CommandProcessingResult delinkClient(Long userId, Long clientId);

  CommandProcessingResult delete(Long userId);
}
