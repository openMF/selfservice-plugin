/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.service;

import java.util.Collection;
import org.apache.fineract.infrastructure.configuration.data.ExternalServicesPropertiesData;
import org.apache.fineract.infrastructure.configuration.data.NationalIdCredentialsData;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;

public interface ExternalApiRestServicesPropertiesReadPlatformService {

  NationalIdCredentialsData getNationalIdCredentials();

  NotificationCredentialsData getNotificationCredentials();

  Collection<ExternalServicesPropertiesData> retrieveOne(String serviceName);
}
