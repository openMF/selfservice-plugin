/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.ExternalServiceConfigurationData;
import org.apache.fineract.infrastructure.configuration.data.ExternalServicePropertyData;
import org.apache.fineract.infrastructure.configuration.domain.ExternalServicePropertiesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalServiceConfigurationServiceImpl
    implements ExternalServiceConfigurationService {

  private final ExternalServicePropertiesRepository repository;

  @Override
  @Transactional(readOnly = true)
  public ExternalServiceConfigurationData getConfiguration(String serviceName) {
    List<ExternalServicePropertyData> rows = repository.findPropertiesByServiceName(serviceName);
    Map<String, String> props = new HashMap<>();
    for (ExternalServicePropertyData row : rows) {
      if (row.getName() != null && row.getValue() != null) {
        props.put(row.getName(), row.getValue());
      }
    }
    log.debug(
        "Resolved {} properties for external service '{}' in current tenant",
        props.size(),
        serviceName);
    return ExternalServiceConfigurationData.builder()
        .serviceName(serviceName)
        .properties(props)
        .build();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, String> getPropertiesAsMap(String serviceName) {
    return getConfiguration(serviceName).getProperties();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isServiceEnabled(String serviceName) {
    return getConfiguration(serviceName).isEnabled();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isServiceRegistered(String serviceName) {
    return repository.existsByServiceName(serviceName);
  }
}
