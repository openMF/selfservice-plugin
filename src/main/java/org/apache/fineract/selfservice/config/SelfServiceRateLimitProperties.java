package org.apache.fineract.selfservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.ExternalServiceConfigurationData;
import org.apache.fineract.infrastructure.configuration.service.ExternalServiceConfigurationService;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for self-service rate limiting.
 * Fetches values dynamically from the c_external_service and c_external_service_properties tables.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelfServiceRateLimitProperties {

    private final ExternalServiceConfigurationService externalServiceConfigurationService;
    private static final String SERVICE_NAME = "SELF_SERVICE_RATE_LIMIT";

    /**
     * @return Maximum allowed attempts per specific resource (e.g. account ID) within the time window. Fallback is 10.
     */
    public int perResource() {
        return getIntProperty("perResource", 10);
    }

    /**
     * @return Maximum allowed total attempts across all resources for the user within the time window. Fallback is 25.
     */
    public int global() {
        return getIntProperty("global", 25);
    }

    /**
     * @return The rolling time window in minutes for which limits are evaluated. Fallback is 15.
     */
    public int windowMinutes() {
        return getIntProperty("windowMinutes", 15);
    }

    /**
     * @return The number of days to retain access audit records before purging. Fallback is 30.
     */
    public int retentionDays() {
        return getIntProperty("retentionDays", 30);
    }

    private int getIntProperty(String key, int defaultValue) {
        try {
            ExternalServiceConfigurationData config = externalServiceConfigurationService.getConfiguration(SERVICE_NAME);
            if (config != null && config.getProperties().containsKey(key)) {
                int parsedValue = Integer.parseInt(config.get(key));
                if (parsedValue > 0) {
                    return parsedValue;
                } else {
                    log.warn("Rate limit property '{}' must be greater than zero, but was {}. Falling back to default {}", key, parsedValue, defaultValue);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch or parse rate limit property '{}' from external service configuration, falling back to default {}", key, defaultValue);
        }
        return defaultValue;
    }
}
