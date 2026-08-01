/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
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
import org.apache.fineract.infrastructure.core.service.DateUtils;
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
        log.info(
            "Resolving timezone for tenantIdentifier='{}'",
            tenant.getTenantIdentifier());

        Optional<String> tzOffset =
            tenantDatePreferenceRepository
                .findByTenantId(tenant.getTenantIdentifier())
                .map(TenantDatePreference::getTimezoneOffset);

        if (tzOffset.isPresent() && StringUtils.isNotBlank(tzOffset.get())) {
          ZoneId zone = ZoneId.of(tzOffset.get());
          log.info(
              "Tenant '{}' timezone resolved to '{}'",
              tenant.getTenantIdentifier(),
              zone);
          return zone;
        }

        log.info(
            "No timezone preference found for tenant '{}', using system default",
            tenant.getTenantIdentifier());
      } else {
        log.info("No tenant context available, using system default ZoneId");
      }
    } catch (Exception e) {
      log.warn(
          "Could not resolve tenant timezone, falling back to system default",
          e);
    }

    ZoneId fallback = ZoneId.systemDefault();
    log.info("Falling back to system default ZoneId='{}'", fallback);
    return fallback;
  }

  /**
   * Centralized method to parse and format transaction dates for Apache Fineract.
   *
   * <p>Uses tenant-aware {@link LocalDateTime} combined with the client {@link LocalDate}
   * to guarantee multi-tenant timezone safety and to avoid "future date" validation issues.
   *
   * @param dateStr    raw date string supplied by the client (may be blank)
   * @param dateFormat client date pattern (defaults to {@code dd-MM-yyyy})
   * @param localeStr  BCP-47 locale tag (defaults to {@code en})
   * @return ISO-8601 offset date-time string ready for Fineract command payloads
   */
  public String formatTransactionDateForFineract(
      String dateStr, String dateFormat, String localeStr) {

    String tenantId = resolveTenantIdentifierForLog();
    log.info(
        "[tenant={}] formatTransactionDateForFineract invoked – dateStr='{}', dateFormat='{}', localeStr='{}'",
        tenantId,
        dateStr,
        dateFormat,
        localeStr);

    if (StringUtils.isNotBlank(dateStr)) {
      try {
        String pattern =
            StringUtils.isNotBlank(dateFormat) ? dateFormat : "dd-MM-yyyy";
        Locale locale =
            StringUtils.isNotBlank(localeStr)
                ? Locale.forLanguageTag(localeStr)
                : Locale.ENGLISH;
        DateTimeFormatter clientFormatter =
            DateTimeFormatter.ofPattern(pattern, locale);

        log.info(
            "[tenant={}] Parsing client date='{}' with pattern='{}' and locale='{}'",
            tenantId,
            dateStr,
            pattern,
            locale.toLanguageTag());

        // 1. Parse the client's date
        LocalDate clientDate = LocalDate.parse(dateStr, clientFormatter);
        log.info("[tenant={}] Client LocalDate parsed successfully → {}", tenantId, clientDate);

        // 2. Get current tenant-aware datetime (CRITICAL: prevents "future date" validation errors)
        LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
        log.info(
            "[tenant={}] Current tenant LocalDateTime (DateUtils) → {}",
            tenantId,
            now);

        // 3. Combine client date with current time to avoid "future date" issues
        LocalDateTime transferDateTime = clientDate.atTime(now.toLocalTime());
        log.info(
            "[tenant={}] Combined LocalDateTime (client date + tenant time) → {}",
            tenantId,
            transferDateTime);

        // 4. Convert to OffsetDateTime using tenant zone for multi-tenant safety
        ZoneId tenantZone = resolveTenantZoneId();
        OffsetDateTime offsetDateTime =
            transferDateTime.atZone(tenantZone).toOffsetDateTime();
        String formatted = offsetDateTime.format(FINERACT_OFFSET_DATETIME_FMT);

        log.info(
            "[tenant={}] Transaction date formatted for Fineract – "
                + "clientDate='{}', tenantZone='{}', offsetDateTime='{}', formatted='{}'",
            tenantId,
            clientDate,
            tenantZone,
            offsetDateTime,
            formatted);
        return formatted;

      } catch (Exception e) {
        log.warn(
            "[tenant={}] Failed to parse client transactionDate='{}' (format='{}', locale='{}'). "
                + "Falling back to current tenant OffsetDateTime",
            tenantId,
            dateStr,
            dateFormat,
            localeStr,
            e);

        ZoneId tenantZone = resolveTenantZoneId();
        OffsetDateTime fallback =
            DateUtils.getLocalDateTimeOfTenant()
                .atZone(tenantZone)
                .toOffsetDateTime();
        String formatted = fallback.format(FINERACT_OFFSET_DATETIME_FMT);

        log.info(
            "[tenant={}] Fallback transaction date used – tenantZone='{}', offsetDateTime='{}', formatted='{}'",
            tenantId,
            tenantZone,
            fallback,
            formatted);
        return formatted;
      }
    } else {
      log.info(
          "[tenant={}] No client date supplied – returning current tenant OffsetDateTime",
          tenantId);

      ZoneId tenantZone = resolveTenantZoneId();
      OffsetDateTime now =
          DateUtils.getLocalDateTimeOfTenant()
              .atZone(tenantZone)
              .toOffsetDateTime();
      String formatted = now.format(FINERACT_OFFSET_DATETIME_FMT);

      log.info(
          "[tenant={}] Current tenant date used (no client input) – tenantZone='{}', offsetDateTime='{}', formatted='{}'",
          tenantId,
          tenantZone,
          now,
          formatted);
      return formatted;
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
    String tenantId = resolveTenantIdentifierForLog();

    if (StringUtils.isBlank(dateStr)) {
      log.info("[tenant={}] parseFineractDate called with blank input – returning null", tenantId);
      return null;
    }

    log.info("[tenant={}] Attempting to parse Fineract date string → '{}'", tenantId, dateStr);

    try {
      OffsetDateTime parsed =
          OffsetDateTime.parse(dateStr, FINERACT_OFFSET_DATETIME_FMT);
      log.info(
          "[tenant={}] Successfully parsed with ISO_OFFSET_DATE_TIME → {}",
          tenantId,
          parsed);
      return parsed;
    } catch (Exception e) {
      log.warn(
          "[tenant={}] Failed to parse Fineract date '{}' with ISO_OFFSET_DATE_TIME, "
              + "attempting plain ISO fallback",
          tenantId,
          dateStr,
          e);
      try {
        OffsetDateTime parsed = OffsetDateTime.parse(dateStr);
        log.info(
            "[tenant={}] Successfully parsed with plain ISO fallback → {}",
            tenantId,
            parsed);
        return parsed;
      } catch (Exception ex) {
        log.error(
            "[tenant={}] Completely failed to parse date '{}'. "
                + "Neither ISO_OFFSET_DATE_TIME nor plain ISO succeeded",
            tenantId,
            dateStr,
            ex);
        throw new IllegalArgumentException("Invalid date format: " + dateStr, ex);
      }
    }
  }

  /**
   * Gets the current date and time for the current tenant as a {@link LocalDateTime}.
   * Delegates to Fineract's {@link DateUtils} to ensure perfect alignment with internal
   * validation logic (e.g., token expiration checks).
   */
  public LocalDateTime getCurrentTenantLocalDateTime() {
    LocalDateTime now = DateUtils.getLocalDateTimeOfTenant();
    log.info(
        "[tenant={}] getCurrentTenantLocalDateTime → {}",
        resolveTenantIdentifierForLog(),
        now);
    return now;
  }

  /**
   * Gets the current date for the current tenant, formatted specifically for
   * Fineract client JSON payloads (e.g., submittedOnDate).
   *
   * <p>Uses {@link DateUtils#getLocalDateOfTenant()} instead of {@code LocalDate.now()}
   * to guarantee the date is NEVER considered "in the future" by Fineract's
   * internal Client.validate() checks.
   */
  public String getCurrentDateForFineract(String dateFormat, String localeStr) {
    String tenantId = resolveTenantIdentifierForLog();
    LocalDate today = DateUtils.getLocalDateOfTenant();
    String pattern = StringUtils.isNotBlank(dateFormat) ? dateFormat : "yyyy-MM-dd";
    Locale locale =
        StringUtils.isNotBlank(localeStr)
            ? Locale.forLanguageTag(localeStr)
            : Locale.ENGLISH;

    String formatted = today.format(DateTimeFormatter.ofPattern(pattern, locale));

    log.info(
        "[tenant={}] getCurrentDateForFineract – today='{}', pattern='{}', locale='{}', formatted='{}'",
        tenantId,
        today,
        pattern,
        locale.toLanguageTag(),
        formatted);
    return formatted;
  }

  // ------------------------------------------------------------------
  // Internal helpers for consistent monitoring context
  // ------------------------------------------------------------------

  /**
   * Best-effort extraction of the current tenant identifier for structured logging.
   * Never throws; returns {@code "unknown"} when context is unavailable.
   */
  private String resolveTenantIdentifierForLog() {
    try {
      FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
      if (tenant != null && StringUtils.isNotBlank(tenant.getTenantIdentifier())) {
        return tenant.getTenantIdentifier();
      }
    } catch (Exception ignored) {
      // never let logging context resolution break the business path
    }
    return "unknown";
  }
}