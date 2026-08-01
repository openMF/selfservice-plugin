package org.apache.fineract.infrastructure.core.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.TenantDatePreference;
import org.apache.fineract.infrastructure.core.repository.TenantDatePreferenceRepository;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.stereotype.Component;

/**
 * Centralized multi-tenant transaction-date utility.
 *
 * <p>Combines a client-supplied {@link LocalDate} with the current {@link OffsetTime}
 * of the tenant's zone so that Apache Fineract never rejects the date as "future"
 * while preserving full timezone integrity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionDateUtil {

  public static final DateTimeFormatter FINERACT_OFFSET_DATETIME_FMT =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final TenantDatePreferenceRepository tenantDatePreferenceRepository;

  /**
   * Resolves the {@link ZoneId} for the current tenant.
   * Falls back to the JVM system default when no tenant context is present
   * or when the tenant has no timezone preference configured.
   */
  private ZoneId resolveTenantZoneId() {
    try {
      FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
      if (tenant != null && StringUtils.isNotBlank(tenant.getTenantIdentifier())) {
        log.debug(
            "Resolving timezone for tenantIdentifier='{}'",
            tenant.getTenantIdentifier());

        Optional<String> tzOffset =
            tenantDatePreferenceRepository
                .findByTenantId(tenant.getTenantIdentifier())
                .map(TenantDatePreference::getTimezoneOffset);

        if (tzOffset.isPresent() && StringUtils.isNotBlank(tzOffset.get())) {
          ZoneId zone = ZoneId.of(tzOffset.get());
          log.debug(
              "Tenant '{}' timezone resolved to '{}'",
              tenant.getTenantIdentifier(),
              zone);
          return zone;
        }

        log.debug(
            "No timezone preference found for tenant '{}', using system default",
            tenant.getTenantIdentifier());
      } else {
        log.debug("No tenant context available, using system default ZoneId");
      }
    } catch (Exception e) {
      log.warn(
          "Could not resolve tenant timezone, falling back to system default",
          e);
    }
    ZoneId fallback = ZoneId.systemDefault();
    log.debug("Falling back to system default ZoneId='{}'", fallback);
    return fallback;
  }

  /**
   * Centralized method to parse and format transaction dates for Apache Fineract.
   *
   * <p>Uses {@link OffsetTime} combined with the client {@link LocalDate} to guarantee
   * multi-tenant timezone safety and to avoid "future date" validation issues.
   *
   * @param dateStr    raw date string supplied by the client (may be blank)
   * @param dateFormat client date pattern (defaults to {@code dd-MM-yyyy})
   * @param localeStr  BCP-47 locale tag (defaults to {@code en})
   * @return ISO-8601 offset date-time string ready for Fineract command payloads
   */
  public String formatTransactionDateForFineract(
      String dateStr, String dateFormat, String localeStr) {

    String targetLocale = StringUtils.isNotBlank(localeStr) ? localeStr : "en";
    ZoneId tenantZone = resolveTenantZoneId();

    log.info(
        "formatTransactionDateForFineract invoked - dateStr='{}', dateFormat='{}', "
            + "localeStr='{}' (resolved='{}'), tenantZone='{}'",
        dateStr,
        dateFormat,
        localeStr,
        targetLocale,
        tenantZone);

    if (StringUtils.isNotBlank(dateStr)) {
      try {
        String pattern =
            StringUtils.isNotBlank(dateFormat) ? dateFormat : "dd-MM-yyyy";
        DateTimeFormatter clientFormatter =
            DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(targetLocale));

        log.info(
            "Parsing client date='{}' with pattern='{}' and locale='{}'",
            dateStr,
            pattern,
            targetLocale);

        // 1. Parse the client's date
        LocalDate clientDate = LocalDate.parse(dateStr, clientFormatter);
        log.info("Client LocalDate parsed successfully {}", clientDate);

        // 2. Get current instant in the tenant zone
        OffsetDateTime now = OffsetDateTime.now(tenantZone);
        log.info("Current OffsetDateTime in tenant zone {}", now);

        // 3. Extract OffsetTime to keep timezone integrity
        OffsetTime currentTime = now.toOffsetTime();
        log.info("Extracted OffsetTime {}", currentTime);

        // 4. Combine client date with current tenant time (preserving offset)
        OffsetDateTime transactionDateTime = clientDate.atTime(currentTime);
        String formatted = transactionDateTime.format(FINERACT_OFFSET_DATETIME_FMT);

        log.debug(
            "Combined transaction OffsetDateTime {} | formatted for Fineract → '{}'",
            transactionDateTime,
            formatted);
        return formatted;

      } catch (Exception e) {
        log.warn(
            "Failed to parse client transactionDate='{}' (format='{}', locale='{}'). "
                + "Falling back to current OffsetDateTime in zone '{}'",
            dateStr,
            dateFormat,
            targetLocale,
            tenantZone,
            e);
        String fallback =
            OffsetDateTime.now(tenantZone).format(FINERACT_OFFSET_DATETIME_FMT);
        log.info("Fallback formatted value '{}'", fallback);
        return fallback;
      }
    } else {
      log.info(
          "No client date supplied - returning current OffsetDateTime in zone '{}'",
          tenantZone);
      String nowFormatted =
          OffsetDateTime.now(tenantZone).format(FINERACT_OFFSET_DATETIME_FMT);
      log.info("Current OffsetDateTime formatted → '{}'", nowFormatted);
      return nowFormatted;
    }
  }

  /**
   * Parses a Fineract-formatted (ISO-8601 offset) date string back to {@link OffsetDateTime}.
   *
   * @param dateStr the string previously produced by {@link #formatTransactionDateForFineract}
   * @return the parsed instant, or {@code null} when the input is blank
   * @throws IllegalArgumentException when the string cannot be parsed by any known strategy
   */
  public OffsetDateTime parseFineractDate(String dateStr) {
    if (StringUtils.isBlank(dateStr)) {
      log.info("parseFineractDate called with blank input – returning null");
      return null;
    }

    log.info("Attempting to parse Fineract date string → '{}'", dateStr);
    try {
      OffsetDateTime parsed =
          OffsetDateTime.parse(dateStr, FINERACT_OFFSET_DATETIME_FMT);
      log.info("Successfully parsed with ISO_OFFSET_DATE_TIME → {}", parsed);
      return parsed;
    } catch (Exception e) {
      log.warn(
          "Failed to parse Fineract date '{}' with ISO_OFFSET_DATE_TIME, "
              + "attempting plain ISO fallback",
          dateStr,
          e);
      try {
        OffsetDateTime parsed = OffsetDateTime.parse(dateStr);
        log.info("Successfully parsed with plain ISO fallback → {}", parsed);
        return parsed;
      } catch (Exception ex) {
        log.error(
            "Completely failed to parse date '{}'. Neither ISO_OFFSET_DATE_TIME "
                + "nor plain ISO succeeded",
            dateStr,
            ex);
        throw new IllegalArgumentException("Invalid date format: " + dateStr, ex);
      }
    }
  }
  
    /**
     * Gets the current date and time for the current tenant as a LocalDateTime.
     * Ensures tenant timezone awareness while maintaining backward compatibility 
     * with existing domain models that expect LocalDateTime.
     */
    public LocalDateTime getCurrentTenantLocalDateTime() {
        return LocalDateTime.now(resolveTenantZoneId());
    }

    /**
     * Gets the current date for the current tenant, formatted specifically for 
     * Fineract client JSON payloads (e.g., submittedOnDate).
     */
    public String getCurrentDateForFineract(String dateFormat, String localeStr) {
        ZoneId tenantZone = resolveTenantZoneId();
        LocalDate today = LocalDate.now(tenantZone);
        String pattern = StringUtils.isNotBlank(dateFormat) ? dateFormat : "yyyy-MM-dd";
        Locale locale = StringUtils.isNotBlank(localeStr) ? Locale.forLanguageTag(localeStr) : Locale.ENGLISH;
        return today.format(DateTimeFormatter.ofPattern(pattern, locale));
    }
}