/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.ExternalServicePropertyData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation. Multi-tenant by construction: JdbcTemplate is bound to the
 * tenant datasource selected by Fineract's routing infrastructure for the request.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ExternalServicePropertiesRepositoryImpl implements ExternalServicePropertiesRepository {

  private static final String SQL_PROPERTIES_BY_SERVICE_NAME =
      """
      SELECT p.name, p.value
      FROM c_external_service_properties p
      INNER JOIN c_external_service s ON p.external_service_id = s.id
      WHERE s.name = ?
      """;

  private static final String SQL_SERVICE_ID_BY_NAME =
      """
      SELECT id FROM c_external_service WHERE name = ?
      """;

  private static final String SQL_EXISTS_BY_NAME =
      """
      SELECT COUNT(*) FROM c_external_service WHERE name = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public List<ExternalServicePropertyData> findPropertiesByServiceName(String serviceName) {
    log.debug("Loading external service properties for serviceName={}", serviceName);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_PROPERTIES_BY_SERVICE_NAME, serviceName);
    List<ExternalServicePropertyData> result = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      String name = (String) row.get("name");
      String value = (String) row.get("value");
      if (name != null) {
        result.add(ExternalServicePropertyData.builder().name(name).value(value).build());
      }
    }
    return result;
  }

  @Override
  public Optional<Long> findExternalServiceIdByName(String serviceName) {
    List<Long> ids =
        jdbcTemplate.query(SQL_SERVICE_ID_BY_NAME, (rs, rowNum) -> rs.getLong("id"), serviceName);
    return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
  }

  @Override
  public boolean existsByServiceName(String serviceName) {
    Long count = jdbcTemplate.queryForObject(SQL_EXISTS_BY_NAME, Long.class, serviceName);
    return count != null && count > 0;
  }
}