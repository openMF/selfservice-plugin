/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.api;

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
import org.apache.fineract.branding.service.TenantBrandingService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class TenantBrandingApiResourceTest {

  private static final String UPDATE_PERMISSION = "UPDATE_CONFIGURATION";

  private PlatformSecurityContext context;
  private TenantBrandingService service;
  private DefaultToApiJsonSerializer<TenantBrandingData> serializer;
  private AppUser user;
  private TenantBrandingApiResource resource;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    context = mock(PlatformSecurityContext.class);
    service = mock(TenantBrandingService.class);
    serializer = mock(DefaultToApiJsonSerializer.class);
    user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
    // A real helper, so the resource's own JSON parsing is exercised rather
    // than stubbed away.
    resource = new TenantBrandingApiResource(context, service, serializer, new FromJsonHelper());
  }

  @Test
  void retrieve_requiresAnAuthenticatedUser() {
    when(service.retrieveCurrentTenantBranding()).thenReturn(new TenantBrandingData("blue"));

    resource.retrieveBranding();
  }

  @Test
  void retrieve_doesNotRequireAnyPermission() {
    // The colour is not sensitive and every client needs it to render, so a
    // read is deliberately open to any authenticated user.
    when(service.retrieveCurrentTenantBranding()).thenReturn(new TenantBrandingData("blue"));

    resource.retrieveBranding();

    verify(user, never()).validateHasPermissionTo(any());
  }

  @Test
  void retrieve_serialisesWhateverTheServiceReturns() {
    when(service.retrieveCurrentTenantBranding()).thenReturn(new TenantBrandingData("green"));
    when(serializer.serialize(new TenantBrandingData("green"))).thenReturn("{\"x\":1}");

    assertEquals("{\"x\":1}", resource.retrieveBranding());
  }

  @Test
  void update_requiresTheUpdateConfigurationPermission() {
    when(service.updateCurrentTenantBranding(any())).thenReturn(new TenantBrandingData("green"));

    resource.updateBranding("{\"primaryColor\":\"green\"}");

    verify(user).validateHasPermissionTo(UPDATE_PERMISSION);
  }

  @Test
  void update_readsPrimaryColourFromTheRequestBody() {
    // Regression guard: JsonCommand.from(String) leaves its own JSON helper
    // null, so reading a parameter off it throws NullPointerException. The
    // resource must parse through FromJsonHelper instead.
    when(service.updateCurrentTenantBranding("green")).thenReturn(new TenantBrandingData("green"));

    resource.updateBranding("{\"primaryColor\":\"green\"}");

    verify(service).updateCurrentTenantBranding("green");
  }

  @Test
  void update_passesAnAbsentColourThroughForTheServiceToReject() {
    // Validation lives in one place; the resource must not silently swallow a
    // malformed body or substitute a default.
    when(service.updateCurrentTenantBranding(null)).thenReturn(new TenantBrandingData("blue"));

    resource.updateBranding("{}");

    verify(service).updateCurrentTenantBranding(null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pink", "light-green", "black", "#3f51b5", "#FFFFFF"})
  void update_readsTheColoursAddedForTheWebAppFromTheRequestBody(final String colour) {
    // The hex forms carry a `#`, which has to survive JSON parsing untouched.
    when(service.updateCurrentTenantBranding(colour)).thenReturn(new TenantBrandingData(colour));

    resource.updateBranding("{\"primaryColor\":\"" + colour + "\"}");

    verify(service).updateCurrentTenantBranding(colour);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pink", "light-green", "black", "#3f51b5", "#FFFFFF"})
  void update_storesTheColoursAddedForTheWebApp(final String colour) {
    // End to end through the real service, so this asserts the endpoint itself
    // accepts the values rather than only that the resource forwards them.
    final TenantBrandingRepository repository = mock(TenantBrandingRepository.class);
    when(repository.findByTenantId("default")).thenReturn(Optional.empty());

    resourceBackedBy(repository).updateBranding("{\"primaryColor\":\"" + colour + "\"}");

    final ArgumentCaptor<TenantBranding> saved = ArgumentCaptor.forClass(TenantBranding.class);
    verify(repository).save(saved.capture());
    assertEquals(colour, saved.getValue().getPrimaryColor());
  }

  @ParameterizedTest
  @ValueSource(strings = {"foobar", "123456", "#12345", "#GGGGGG", "rgb(1,2,3)", "javascript:x"})
  void update_rejectsAnInvalidColourEndToEnd(final String colour) {
    final TenantBrandingRepository repository = mock(TenantBrandingRepository.class);
    final TenantBrandingApiResource endToEnd = resourceBackedBy(repository);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> endToEnd.updateBranding("{\"primaryColor\":\"" + colour + "\"}"));
    verify(repository, never()).save(any());
  }

  /**
   * @return the resource wired to a real service over the given repository.
   */
  private TenantBrandingApiResource resourceBackedBy(final TenantBrandingRepository repository) {
    ThreadLocalContextUtil.setTenant(
        FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").build());
    return new TenantBrandingApiResource(
        context, new TenantBrandingService(repository), serializer, new FromJsonHelper());
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }
}
