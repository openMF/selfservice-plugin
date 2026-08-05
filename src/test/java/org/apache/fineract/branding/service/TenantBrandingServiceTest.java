/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.branding.data.TenantBrandingData;
import org.apache.fineract.branding.domain.TenantBranding;
import org.apache.fineract.branding.domain.TenantBrandingRepository;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenantBrandingServiceTest {

  private TenantBrandingRepository repository;
  private TenantBrandingService service;

  private static void currentTenant(final String identifier) {
    ThreadLocalContextUtil.setTenant(
        FineractPlatformTenant.builder().id(1L).tenantIdentifier(identifier).build());
  }

  @BeforeEach
  void setUp() {
    repository = mock(TenantBrandingRepository.class);
    service = new TenantBrandingService(repository);
    currentTenant("default");
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void retrieve_defaultsToBlueWhenTenantHasNoRow() {
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    assertEquals("blue", service.retrieveCurrentTenantBranding().primaryColor());
  }

  @Test
  void retrieve_returnsTheStoredColour() {
    when(repository.findByTenantId("default"))
        .thenReturn(Optional.of(branding("default", "green")));

    assertEquals("green", service.retrieveCurrentTenantBranding().primaryColor());
  }

  @Test
  void retrieve_defaultsWhenTheStoredColourIsNull() {
    // The column is nullable, and rows can be written outside this service.
    when(repository.findByTenantId("default")).thenReturn(Optional.of(branding("default", null)));

    assertEquals("blue", service.retrieveCurrentTenantBranding().primaryColor());
  }

  @Test
  void retrieve_defaultsWhenTheStoredColourIsNoLongerSupported() {
    // A colour can be retired while rows still reference it; a client must
    // never be handed a value it cannot render.
    when(repository.findByTenantId("default"))
        .thenReturn(Optional.of(branding("default", "chartreuse")));

    assertEquals("blue", service.retrieveCurrentTenantBranding().primaryColor());
  }

  @Test
  void retrieve_normalisesTheStoredColour() {
    when(repository.findByTenantId("default"))
        .thenReturn(Optional.of(branding("default", "  GREEN ")));

    assertEquals("green", service.retrieveCurrentTenantBranding().primaryColor());
  }

  @Test
  void retrieve_isScopedToTheCurrentTenant() {
    // Branding is tenant wide, so the row is looked up by the tenant on the
    // request context and never by a caller supplied identifier.
    currentTenant("acme");
    when(repository.findByTenantId("acme")).thenReturn(Optional.of(branding("acme", "purple")));

    assertEquals("purple", service.retrieveCurrentTenantBranding().primaryColor());
    verify(repository).findByTenantId("acme");
  }

  @Test
  void retrieve_withoutATenantContext_isIndistinguishableFromAnUnconfiguredTenant() {
    // Pins the failure mode that makes the filter chain wiring worth asserting
    // (see TenantBrandingSecurityFilterChainIntegrationTest).
    //
    // The tenant is read off the request context, and that context is populated
    // by TenantAwareAuthenticationFilter - a security filter. A request on a
    // path no filter chain claims therefore arrives with no tenant at all, and
    // this method answers "blue" rather than failing: exactly what it answers
    // for a tenant that has never chosen a colour.
    //
    // So a server side misconfiguration reads as a legitimate default, and the
    // client cannot tell either, because blue is also its fallback. Every
    // tenant silently loses its branding with nothing anywhere reporting it.
    ThreadLocalContextUtil.clearTenant();
    when(repository.findByTenantId(null)).thenReturn(Optional.empty());

    assertEquals("blue", service.retrieveCurrentTenantBranding().primaryColor());
    verify(repository).findByTenantId(null);
  }

  @Test
  void update_createsTheRowOnFirstUse() {
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    final TenantBrandingData result = service.updateCurrentTenantBranding("green");

    final ArgumentCaptor<TenantBranding> saved = ArgumentCaptor.forClass(TenantBranding.class);
    verify(repository).save(saved.capture());
    assertEquals("default", saved.getValue().getTenantId());
    assertEquals("green", saved.getValue().getPrimaryColor());
    assertEquals("green", result.primaryColor());
  }

  @Test
  void update_mutatesTheExistingRowRatherThanAddingAnother() {
    final TenantBranding existing = branding("default", "blue");
    when(repository.findByTenantId("default")).thenReturn(Optional.of(existing));

    service.updateCurrentTenantBranding("red");

    assertEquals("red", existing.getPrimaryColor());
    verify(repository).save(existing);
  }

  @Test
  void update_normalisesCaseAndSurroundingWhitespace() {
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    assertEquals("orange", service.updateCurrentTenantBranding("  ORANGE  ").primaryColor());
  }

  @Test
  void update_rejectsAnUnsupportedColour() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.updateCurrentTenantBranding("chartreuse"));
    verify(repository, never()).save(any());
  }

  @Test
  void update_rejectsAMissingColour() {
    assertThrows(
        PlatformApiDataValidationException.class, () -> service.updateCurrentTenantBranding(null));
    verify(repository, never()).save(any());
  }

  @Test
  void update_rejectsABlankColour() {
    assertThrows(
        PlatformApiDataValidationException.class, () -> service.updateCurrentTenantBranding("   "));
    verify(repository, never()).save(any());
  }

  @Test
  void supportedColoursAreLegibleChoicesAndIncludeTheDefault() {
    // The client renders white label text on these, so the set is deliberately
    // fixed rather than free form.
    assertEquals(
        java.util.List.of("blue", "green", "purple", "orange", "red", "yellow"),
        TenantBrandingService.SUPPORTED_PRIMARY_COLORS);
    org.junit.jupiter.api.Assertions.assertTrue(
        TenantBrandingService.SUPPORTED_PRIMARY_COLORS.contains(
            TenantBrandingService.DEFAULT_PRIMARY_COLOR));
  }

  private static TenantBranding branding(final String tenantId, final String color) {
    final TenantBranding branding = new TenantBranding();
    branding.setTenantId(tenantId);
    branding.setPrimaryColor(color);
    return branding;
  }
}
