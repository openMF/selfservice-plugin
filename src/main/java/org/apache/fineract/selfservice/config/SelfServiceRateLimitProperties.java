package org.apache.fineract.selfservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.ExternalServiceConfigurationData;
import org.apache.fineract.infrastructure.configuration.service.ExternalServiceConfigurationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuration properties for self-service rate limiting.
 * Fetches values dynamically from the c_external_service tables when available,
 * otherwise falls back to safe defaults.  All lookups are performed in a
 * completely independent transaction so a missing table can never abort the
 * caller’s transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelfServiceRateLimitProperties {

  private final ExternalServiceConfigurationService externalServiceConfigurationService;
  private static final String SERVICE_NAME = "SELF_SERVICE_RATE_LIMIT";

  public int perResource() {
    return getIntProperty("perResource", 10);
  }

  public int global() {
    return getIntProperty("global", 25);
  }

  public int windowMinutes() {
    return getIntProperty("windowMinutes", 15);
  }

  public int retentionDays() {
    return getIntProperty("retentionDays", 30);
  }

  /**
   * Runs in its own transaction (REQUIRES_NEW) so any SQLException
   * (missing table, wrong tenant, etc.) is completely isolated.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  protected int getIntProperty(String key, int defaultValue) {
    try {
      ExternalServiceConfigurationData config =
          externalServiceConfigurationService.getConfiguration(SERVICE_NAME);
      if (config != null && config.getProperties() != null && config.getProperties().containsKey(key)) {
        int parsedValue = Integer.parseInt(config.get(key));
        if (parsedValue > 0) {
          return parsedValue;
        }
        log.warn(
            "Rate limit property '{}' must be > 0 but was {}. Falling back to default {}",
            key, parsedValue, defaultValue);
      }
    } catch (Exception e) {
      // Table may not exist, tenant context may be missing, etc.
      log.warn(
          "Failed to fetch rate-limit property '{}' from external service configuration "
              + "(falling back to default {}). Cause: {}",
          key, defaultValue, e.getMessage());
    }
    return defaultValue;
  }
}