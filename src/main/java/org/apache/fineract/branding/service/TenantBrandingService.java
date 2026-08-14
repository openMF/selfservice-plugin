/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
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
   * Named colours a client application is expected to render. Each one has to carry white label
   * text at WCAG AA in the clients that consume it, which is why the named set stays curated rather
   * than growing to cover every colour a tenant might want; a tenant that wants one of those picks
   * a hex colour instead.
   *
   * <p>Values are stored exactly as listed here, so entries may be added but must not be renamed:
   * existing rows reference them.
   */
  public static final List<String> SUPPORTED_PRIMARY_COLORS =
      List.of("blue", "green", "purple", "orange", "red", "yellow", "pink", "light-green", "black");

  /**
   * A six digit hex colour, the only free form value accepted.
   *
   * <p>Anchored and limited to hex digits so the colour cannot carry anything but a colour: a
   * client drops this value straight into a stylesheet, so shapes such as {@code rgb(...)}, {@code
   * url(...)}, {@code var(...)} or markup must never reach one. Three digit shorthand is rejected
   * too, keeping one stored form per colour.
   */
  private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

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
   * <p>Rows are not only written through {@link #updateCurrentTenantBranding(String)}: the column
   * is nullable for older rows, and a colour can be retired from {@link #SUPPORTED_PRIMARY_COLORS}
   * while rows still reference it. Reading is therefore defensive, so the endpoint never hands a
   * client a value it cannot use.
   *
   * <p>Only an unusable value is replaced. A supported colour - named or hex - is returned as its
   * canonical form and nothing else, so reading is idempotent: a stored {@code #3f51b5} reads back
   * as {@code #3f51b5} however many times it is read, rather than drifting towards the default or
   * towards another shade.
   *
   * @param storedColor colour as held in the database, possibly null
   * @return a supported colour, never null
   */
  private static String normalisedOrDefault(final String storedColor) {
    final String canonical = canonicalPrimaryColor(storedColor);
    return canonical == null ? DEFAULT_PRIMARY_COLOR : canonical;
  }

  /**
   * @param primaryColor colour as supplied by a client or held in the database
   * @return true when the colour is a supported named colour or a six digit hex colour
   */
  public static boolean isSupportedPrimaryColor(final String primaryColor) {
    return canonicalPrimaryColor(primaryColor) != null;
  }

  /**
   * Reduces a colour to the single form it is stored and served in.
   *
   * <p>A named colour is matched case insensitively and canonicalised to lower case, which is how
   * existing rows are held. A hex colour keeps the case it was given: {@code #FFFFFF} and {@code
   * #ffffff} are the same colour, and rewriting one into the other would change what a client is
   * handed back for no benefit.
   *
   * <p>Case folding is pinned to {@link Locale#ROOT} because the tenant's locale must not decide
   * whether a colour is recognised - under a Turkish locale the default folding turns the {@code I}
   * of {@code LIGHT-GREEN} into a dotless {@code ı}, which would match nothing.
   *
   * @param primaryColor colour as supplied, possibly null
   * @return the canonical colour, or null when it is neither a named nor a hex colour
   */
  private static String canonicalPrimaryColor(final String primaryColor) {
    if (primaryColor == null) {
      return null;
    }
    final String trimmed = primaryColor.trim();
    if (HEX_COLOR_PATTERN.matcher(trimmed).matches()) {
      return trimmed;
    }
    final String named = trimmed.toLowerCase(Locale.ROOT);
    return SUPPORTED_PRIMARY_COLORS.contains(named) ? named : null;
  }

  /**
   * Sets the current tenant's primary colour, creating the row on first use.
   *
   * @param primaryColor one of {@link #SUPPORTED_PRIMARY_COLORS}, or a six digit hex colour
   * @return the stored branding
   * @throws PlatformApiDataValidationException if the colour is missing or unsupported
   */
  @Transactional
  public TenantBrandingData updateCurrentTenantBranding(final String primaryColor) {
    final String canonicalColor = validated(primaryColor);

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

    branding.setPrimaryColor(canonicalColor);
    repository.save(branding);

    return new TenantBrandingData(branding.getPrimaryColor());
  }

  /**
   * Checks a colour and reduces it to the form it is stored in.
   *
   * <p>The two accepted shapes are alternatives rather than successive constraints, so the check
   * cannot be expressed as a chain of {@link DataValidatorBuilder} rules - each rule in a chain
   * adds its own error, and a hex colour would fail the named colour rule. The decision is
   * therefore taken once, in {@link #canonicalPrimaryColor(String)}, and only its outcome is
   * reported through the builder, which keeps one definition of what a supported colour is.
   *
   * @param primaryColor colour as supplied by the client
   * @return the canonical colour
   * @throws PlatformApiDataValidationException if the colour is missing or unsupported
   */
  private String validated(final String primaryColor) {
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder validator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(RESOURCE_NAME)
            .parameter("primaryColor")
            .value(primaryColor)
            .notBlank();

    final String canonicalColor = canonicalPrimaryColor(primaryColor);
    // Reported only when the value is present: a missing colour is already
    // covered by notBlank, and both errors describe the same one mistake.
    if (dataValidationErrors.isEmpty() && canonicalColor == null) {
      validator.failWithCode(
          "is.not.a.supported.colour",
          String.join(", ", SUPPORTED_PRIMARY_COLORS),
          HEX_COLOR_PATTERN.pattern());
    }

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
    return canonicalColor;
  }

  private String currentTenantIdentifier() {
    final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
    return tenant == null ? null : tenant.getTenantIdentifier();
  }
}
