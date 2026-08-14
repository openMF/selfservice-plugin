/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    // The client renders white label text on the named colours, so the set is
    // deliberately curated rather than free form. Pinned in order: the values
    // are stored as written, so one may be added but none renamed.
    assertEquals(
        List.of(
            "blue", "green", "purple", "orange", "red", "yellow", "pink", "light-green", "black"),
        TenantBrandingService.SUPPORTED_PRIMARY_COLORS);
    assertTrue(
        TenantBrandingService.SUPPORTED_PRIMARY_COLORS.contains(
            TenantBrandingService.DEFAULT_PRIMARY_COLOR));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "blue",
        "green",
        "purple",
        "orange",
        "red",
        "yellow",
        "pink",
        "light-green",
        "black"
      })
  void update_acceptsEverySupportedNamedColour(final String colour) {
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    assertEquals(colour, service.updateCurrentTenantBranding(colour).primaryColor());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"#3f51b5", "#FFFFFF", "#000000", "#abcdef", "#ABCDEF", "#FF0000", "#00FF00"})
  void update_acceptsASixDigitHexColour(final String colour) {
    // Stored with the case it was given: #FFFFFF and #ffffff are one colour,
    // and rewriting one into the other would change what a client reads back.
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    assertEquals(colour, service.updateCurrentTenantBranding(colour).primaryColor());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "   ",
        "foobar",
        "blue123",
        "123456",
        "#123",
        "#12345",
        "#1234567",
        "#GGGGGG",
        "rgb(1,2,3)",
        "rgba(1,2,3,1)",
        "red<script>",
        "javascript:alert(1)",
        "url(https://example.invalid/x.png)",
        "var(--brand)",
        "expression(alert(1))",
        "#3f51b5; color: red",
        "#3f51b5 #ffffff"
      })
  void update_rejectsAnythingThatIsNotANamedOrHexColour(final String colour) {
    // The value is dropped into a stylesheet by the clients that read it, so
    // nothing but a colour may be stored.
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.updateCurrentTenantBranding(colour));
    verify(repository, never()).save(any());
  }

  @Test
  void update_rejectsAHexColourCarryingATrailingLineSeparator() {
    // U+2028 is a terminator Java's `$` honours and String.trim leaves alone,
    // so a `$` anchored check alone would accept "#3f51b5<U+2028>payload" as a
    // colour. Pins that the whole value has to be the colour, and keeps the
    // character out of the source, where it is invisible.
    final String lineSeparator = Character.toString(0x2028);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.updateCurrentTenantBranding("#3f51b5" + lineSeparator));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.updateCurrentTenantBranding("#3f51b5" + lineSeparator + "color: red"));
    verify(repository, never()).save(any());
  }

  @Test
  void update_normalisesTheCaseOfANamedColourButNotOfAHexColour() {
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    assertEquals(
        "light-green", service.updateCurrentTenantBranding(" LIGHT-GREEN ").primaryColor());
    assertEquals("#3F51B5", service.updateCurrentTenantBranding("  #3F51B5  ").primaryColor());
  }

  @ParameterizedTest
  @CsvSource({
    "blue, blue",
    "pink, pink",
    "light-green, light-green",
    "black, black",
    "'#3f51b5', '#3f51b5'",
    "'#FFFFFF', '#FFFFFF'",
    "'  PINK  ', pink",
    "'  #3f51b5  ', '#3f51b5'",
    "chartreuse, blue",
    "'rgb(1,2,3)', blue",
    "'#12345', blue"
  })
  void retrieve_returnsASupportedColourUnchangedAndReplacesTheRest(
      final String stored, final String expected) {
    when(repository.findByTenantId("default")).thenReturn(Optional.of(branding("default", stored)));

    assertEquals(expected, service.retrieveCurrentTenantBranding().primaryColor());
  }

  @Test
  void retrieve_doesNotTransformAHexColourOnRepeatedReads() {
    // Guards the failure the clients would see as branding drifting a shade on
    // every page load: reading has to be idempotent, so feeding a read back in
    // as the stored value must settle rather than keep changing it.
    String colour = "#3f51b5";
    for (int read = 0; read < 5; read++) {
      when(repository.findByTenantId("default"))
          .thenReturn(Optional.of(branding("default", colour)));
      colour = service.retrieveCurrentTenantBranding().primaryColor();
    }

    assertEquals("#3f51b5", colour);
  }

  @ParameterizedTest
  @ValueSource(strings = {"blue", "pink", "light-green", "black", "#3f51b5", "#ABCDEF", " BLUE "})
  void isSupportedPrimaryColor_acceptsNamedAndHexColours(final String colour) {
    assertTrue(TenantBrandingService.isSupportedPrimaryColor(colour));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {"", "   ", "foobar", "blue123", "123456", "#12345", "#GGGGGG", "rgb(1,2,3)"})
  void isSupportedPrimaryColor_rejectsAnythingElse(final String colour) {
    assertFalse(TenantBrandingService.isSupportedPrimaryColor(colour));
  }

  private static TenantBranding branding(final String tenantId, final String color) {
    final TenantBranding branding = new TenantBranding();
    branding.setTenantId(tenantId);
    branding.setPrimaryColor(color);
    return branding;
  }
}
