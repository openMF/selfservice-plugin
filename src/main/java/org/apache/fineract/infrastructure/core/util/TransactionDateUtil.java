package org.apache.fineract.infrastructure.core.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.TenantDatePreference;
import org.apache.fineract.infrastructure.core.repository.TenantDatePreferenceRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionDateUtil {

    public static final DateTimeFormatter FINERACT_OFFSET_DATETIME_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    
    private final TenantDatePreferenceRepository tenantDatePreferenceRepository;

    /**
     * Resolves the ZoneId for the current tenant. 
     * Falls back to system default if not configured or if called outside a tenant context.
     */
    private ZoneId resolveTenantZoneId() {
        try {
            FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
            if (tenant != null && StringUtils.isNotBlank(tenant.getTenantIdentifier())) {
                Optional<String> tzOffset = tenantDatePreferenceRepository
                        .findByTenantId(tenant.getTenantIdentifier())
                        .map(TenantDatePreference::getTimezoneOffset);
                
                if (tzOffset.isPresent() && StringUtils.isNotBlank(tzOffset.get())) {
                    return ZoneId.of(tzOffset.get());
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve tenant timezone, falling back to system default", e);
        }
        return ZoneId.systemDefault();
    }

    /**
     * Centralized method to parse and format transaction dates for Apache Fineract.
     * Uses OffsetTime combined with LocalDate to ensure multi-tenant timezone safety 
     * and avoid "future date" validation issues.
     */
    public String formatTransactionDateForFineract(String dateStr, String dateFormat, String localeStr) {
        String targetLocale = StringUtils.isNotBlank(localeStr) ? localeStr : "en";
        ZoneId tenantZone = resolveTenantZoneId();
        
        if (StringUtils.isNotBlank(dateStr)) {
            try {
                String pattern = StringUtils.isNotBlank(dateFormat) ? dateFormat : "dd-MM-yyyy";
                DateTimeFormatter clientFormatter = DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(targetLocale));
                
                // 1. Parse the client's date
                LocalDate clientDate = LocalDate.parse(dateStr, clientFormatter);
                
                // 2. Get current time with the resolved tenant offset
                OffsetDateTime now = OffsetDateTime.now(tenantZone);
                
                // 3. Extract OffsetTime to strictly satisfy the requirement while maintaining timezone integrity
                OffsetTime currentTime = now.toOffsetTime();
                
                // 4. Combine client date with current system/tenant time (keeping the offset)
                OffsetDateTime transactionDateTime = clientDate.atTime(currentTime);
                
                return transactionDateTime.format(FINERACT_OFFSET_DATETIME_FMT);
                
            } catch (Exception e) {
                log.warn("Failed to parse client transactionDate '{}', falling back to current OffsetDateTime", dateStr, e);
                return OffsetDateTime.now(tenantZone).format(FINERACT_OFFSET_DATETIME_FMT);
            }
        } else {
            return OffsetDateTime.now(tenantZone).format(FINERACT_OFFSET_DATETIME_FMT);
        }
    }

    /**
     * Parses a Fineract-formatted date string back to OffsetDateTime.
     */
    public OffsetDateTime parseFineractDate(String dateStr) {
        if (StringUtils.isBlank(dateStr)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(dateStr, FINERACT_OFFSET_DATETIME_FMT);
        } catch (Exception e) {
            log.warn("Failed to parse Fineract date '{}', attempting fallback ISO parsing", dateStr, e);
            try {
                return OffsetDateTime.parse(dateStr);
            } catch (Exception ex) {
                log.error("Completely failed to parse date '{}'", dateStr, ex);
                throw new IllegalArgumentException("Invalid date format: " + dateStr, ex);
            }
        }
    }
}