/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.apache.fineract.infrastructure.configuration.data.ExternalServiceConfigurationData;
import org.apache.fineract.infrastructure.configuration.service.ExternalServiceConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StepUpSettingsProviderTest {

  @Mock private ExternalServiceConfigurationService externalServiceConfigurationService;

  private StepUpSettingsProvider provider;

  @BeforeEach
  void setUp() {
    provider = new StepUpSettingsProvider(externalServiceConfigurationService);
  }

  private void configure(Map<String, String> properties) {
    when(externalServiceConfigurationService.getConfiguration(StepUpSettings.SERVICE_NAME))
        .thenReturn(
            ExternalServiceConfigurationData.builder()
                .serviceName(StepUpSettings.SERVICE_NAME)
                .properties(properties)
                .build());
  }

  @Test
  void currentStrict_readsConfiguredSettings() {
    configure(Map.of("enabled", "true"));

    assertTrue(provider.currentStrict().invalidateSessionsOnPasswordChange());
  }

  @Test
  void currentStrict_missingConfigurationGivesDefaults() {
    configure(Map.of());

    assertFalse(provider.currentStrict().invalidateSessionsOnPasswordChange());
  }

  @Test
  void currentStrict_throwsWhenReadFails() {
    when(externalServiceConfigurationService.getConfiguration(StepUpSettings.SERVICE_NAME))
        .thenThrow(new IllegalStateException("db down"));

    assertThrows(IllegalStateException.class, () -> provider.currentStrict());
  }

  @Test
  void current_fallsBackToDefaultsWhenReadFails() {
    when(externalServiceConfigurationService.getConfiguration(StepUpSettings.SERVICE_NAME))
        .thenThrow(new IllegalStateException("db down"));

    assertFalse(provider.current().enabled());
  }
}
