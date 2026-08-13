package org.apache.fineract.selfservice.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for self-service rate limiting.
 * Binds to properties prefixed with "fineract.selfservice.security.rate-limit".
 *
 * @param perResource   Maximum allowed attempts per specific resource (e.g. account ID) within the time window. Fallback is 10.
 * @param global        Maximum allowed total attempts across all resources for the user within the time window. Fallback is 25.
 * @param windowMinutes The rolling time window in minutes for which limits are evaluated. Fallback is 15.
 * @param retentionDays The number of days to retain access audit records before purging. Fallback is 30.
 */
@Validated
@ConfigurationProperties(prefix = "fineract.selfservice.security.rate-limit")
public record SelfServiceRateLimitProperties(
    @Positive int perResource,
    @Positive int global,
    @Positive int windowMinutes,
    @Positive int retentionDays
) {
  public SelfServiceRateLimitProperties {
    // defaults
    if (perResource <= 0) perResource = 10;
    if (global <= 0) global = 25;
    if (windowMinutes <= 0) windowMinutes = 15;
    if (retentionDays <= 0) retentionDays = 30;
  }
}
