/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.branding.data.TenantBrandingData;
import org.apache.fineract.branding.service.TenantBrandingService;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
