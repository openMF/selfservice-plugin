/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.branding.data.TenantBrandingData;
import org.apache.fineract.branding.domain.TenantBranding;
import org.apache.fineract.branding.domain.TenantBrandingRepository;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and updates the current tenant's branding.
 *
 * <p>The tenant is taken from the request context rather than a parameter, so a caller can only
 * ever read or change the branding of the tenant it authenticated against.
 */
@Service
@RequiredArgsConstructor
public class TenantBrandingService {

  private static final String RESOURCE_NAME = "tenantbranding";

  /** Applied when a tenant has no row, or has one the client cannot interpret. */
  public static final String DEFAULT_PRIMARY_COLOR = "blue";

  /**
   * Colours a client application is expected to render. Kept deliberately small: each one has to
   * carry white label text at WCAG AA in the clients that consume it.
   */
  public static final List<String> SUPPORTED_PRIMARY_COLORS =
      List.of("blue", "green", "purple", "orange", "red", "yellow");

  private final TenantBrandingRepository repository;

  /**
   * @return the current tenant's branding, falling back to the default colour when the tenant has
   *     no row, the stored colour is null, or it is no longer one of the supported colours
   */
  @Transactional(readOnly = true)
  public TenantBrandingData retrieveCurrentTenantBranding() {
    return new TenantBrandingData(
        repository
            .findByTenantId(currentTenantIdentifier())
            .map(TenantBranding::getPrimaryColor)
            .map(TenantBrandingService::normalisedOrDefault)
            .orElse(DEFAULT_PRIMARY_COLOR));
  }

  /**
   * Maps a stored colour onto one a client can actually render.
   *
   * <p>Rows are not only written through {@link #updateCurrentTenantBranding(String)}: the column is
   * nullable for older rows, and a colour can be retired from {@link #SUPPORTED_PRIMARY_COLORS}
   * while rows still reference it. Reading is therefore defensive, so the endpoint never hands a
   * client a value it cannot use.
   *
   * @param storedColor colour as held in the database, possibly null
   * @return a supported colour, never null
   */
  private static String normalisedOrDefault(final String storedColor) {
    final String normalised = storedColor.trim().toLowerCase();
    return SUPPORTED_PRIMARY_COLORS.contains(normalised) ? normalised : DEFAULT_PRIMARY_COLOR;
  }

  /**
   * Sets the current tenant's primary colour, creating the row on first use.
   *
   * @param primaryColor one of {@link #SUPPORTED_PRIMARY_COLORS}
   * @return the stored branding
   * @throws PlatformApiDataValidationException if the colour is missing or unsupported
   */
  @Transactional
  public TenantBrandingData updateCurrentTenantBranding(final String primaryColor) {
    validate(primaryColor);

    final String tenantIdentifier = currentTenantIdentifier();
    final TenantBranding branding =
        repository
            .findByTenantId(tenantIdentifier)
            .orElseGet(
                () -> {
                  final TenantBranding created = new TenantBranding();
                  created.setTenantId(tenantIdentifier);
                  return created;
                });

    branding.setPrimaryColor(primaryColor.trim().toLowerCase());
    repository.save(branding);

    return new TenantBrandingData(branding.getPrimaryColor());
  }

  private void validate(final String primaryColor) {
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    new DataValidatorBuilder(dataValidationErrors)
        .resource(RESOURCE_NAME)
        .parameter("primaryColor")
        .value(primaryColor == null ? null : primaryColor.trim().toLowerCase())
        .notBlank()
        .isOneOfTheseStringValues(SUPPORTED_PRIMARY_COLORS);

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
  }

  private String currentTenantIdentifier() {
    final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    return tenant == null ? null : tenant.getTenantIdentifier();
  }
}
