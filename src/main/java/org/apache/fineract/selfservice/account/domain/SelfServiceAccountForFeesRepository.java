package org.apache.fineract.selfservice.account.domain;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SelfServiceAccountForFeesRepository {

  private final JdbcTemplate jdbcTemplate;

  /**
   * Fetches properties for a given external service name. Multi-tenancy is handled automatically by
   * Fineract's TenantDataSource routing.
   */
  public Map<String, String> getProperties(String serviceName) {
    String sql =
        "SELECT esp.name, esp.value "
            + "FROM c_external_service_properties esp "
            + "JOIN c_external_service es ON esp.external_service_id = es.id "
            + "WHERE es.name = ?";
    Map<String, String> properties = new HashMap<>();
    try {
      jdbcTemplate.query(
          sql,
          new Object[] {serviceName},
          rs -> {
            properties.put(rs.getString("name"), rs.getString("value"));
          });
    } catch (Exception e) {
      log.error(
          "Error fetching external service properties for {}: {}", serviceName, e.getMessage());
    }
    return properties;
  }
}
