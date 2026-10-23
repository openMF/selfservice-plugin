/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.ExternalServiceConfigurationData;
import org.apache.fineract.infrastructure.configuration.service.ExternalServiceConfigurationService;
import org.springframework.stereotype.Component;

/**
 * Reads the per-tenant activity log configuration from the {@code c_external_service} tables
 * (service {@value ActivitySettings#SERVICE_NAME}, seeded by Liquibase). Nothing is cached: the
 * tenant is part of the request context, so a read per call keeps multi-tenant behaviour trivially
 * correct. When the service is missing or unreadable the defaults apply (logged at ERROR).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivitySettingsProvider {

  private final ExternalServiceConfigurationService externalServiceConfigurationService;

  /** Current settings for the tenant bound to the calling thread. */
  public ActivitySettings current() {
    try {
      final ExternalServiceConfigurationData config =
          externalServiceConfigurationService.getConfiguration(ActivitySettings.SERVICE_NAME);
      if (config == null || config.getProperties() == null) {
        return ActivitySettings.defaults();
      }
      final Map<String, String> properties = config.getProperties();
      return ActivitySettings.fromProperties(properties);
    } catch (Exception e) {
      log.error(
          "Could not read activity log configuration '{}'; falling back to defaults. Cause: {}",
          ActivitySettings.SERVICE_NAME,
          e.getMessage());
      return ActivitySettings.defaults();
    }
  }
}
