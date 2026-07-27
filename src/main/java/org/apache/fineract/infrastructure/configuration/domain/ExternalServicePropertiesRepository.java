/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.domain;

import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.configuration.data.ExternalServicePropertyData;

/**
 * Tenant-aware data access for Fineract external service configuration tables. Uses the current
 * tenant connection (RoutingDataSource / ThreadLocal context).
 */
public interface ExternalServicePropertiesRepository {

  /**
   * Returns all property rows for the given external service name in the current tenant. Empty list
   * if the service is not registered or has no properties.
   */
  List<ExternalServicePropertyData> findPropertiesByServiceName(String serviceName);

  /** Returns the external_service id for the given name in the current tenant, if present. */
  Optional<Long> findExternalServiceIdByName(String serviceName);

  /** Whether a row exists in c_external_service for the given name in the current tenant. */
  boolean existsByServiceName(String serviceName);
}
