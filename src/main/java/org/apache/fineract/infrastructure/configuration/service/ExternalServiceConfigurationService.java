/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.service;

import java.util.Map;
import org.apache.fineract.infrastructure.configuration.data.ExternalServiceConfigurationData;

/**
 * Read API for tenant-scoped external service configuration (c_external_service /
 * c_external_service_properties).
 */
public interface ExternalServiceConfigurationService {

  /**
   * Loads all properties for the named external service in the current tenant. Never returns null;
   * properties map may be empty if the service is missing or unconfigured.
   */
  ExternalServiceConfigurationData getConfiguration(String serviceName);

  /** Convenience: property map only (name → value) for the current tenant. */
  Map<String, String> getPropertiesAsMap(String serviceName);

  boolean isServiceEnabled(String serviceName);

  boolean isServiceRegistered(String serviceName);
}
