/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.branding.data.TenantBrandingData;
import org.apache.fineract.branding.service.TenantBrandingService;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/**
 * JAX-RS resource exposing the current tenant's branding under {@code /v1/branding}.
 *
 * <p>Deliberately not under {@code /v1/self}: that prefix is matched by the self-service security
 * chain and authenticates self-service users only, whereas branding is consumed by staff facing
 * clients such as the web app. Sitting outside that prefix leaves the endpoint on Fineract's main
 * security chain, so ordinary platform credentials apply.
 *
 * <p>Reading requires nothing beyond an authenticated user, since the colour is not sensitive and
 * every client needs it to render. Updating requires {@code UPDATE_CONFIGURATION}, matching the
 * permission a user would need to change comparable tenant wide settings.
 */
@Path("/v1/branding")
@Component
@Tag(
    name = "Tenant Branding",
    description =
        "Branding applied by client applications for the authenticated tenant. The value is tenant"
            + " scoped, so every user of a tenant sees the same branding on every device.")
@RequiredArgsConstructor
public class TenantBrandingApiResource {

  private static final String UPDATE_PERMISSION = "UPDATE_CONFIGURATION";

  private final PlatformSecurityContext context;
  private final TenantBrandingService tenantBrandingService;
  private final DefaultToApiJsonSerializer<TenantBrandingData> toApiJsonSerializer;
  private final FromJsonHelper fromApiJsonHelper;

  /**
   * Retrieves the branding of the tenant the caller authenticated against.
   *
   * @return JSON representation of the branding, defaulted when the tenant has never set one
   */
  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Tenant Branding",
      description =
          "Returns the primary colour for the authenticated tenant.\n\n"
              + "Example Request:\n\n"
              + "branding")
  @ApiResponse(responseCode = "200", description = "OK")
  public String retrieveBranding() {
    context.authenticatedUser();
    final TenantBrandingData branding = tenantBrandingService.retrieveCurrentTenantBranding();
    return toApiJsonSerializer.serialize(branding);
  }

  /**
   * Updates the branding of the tenant the caller authenticated against.
   *
   * @param apiRequestBodyAsJson request body carrying {@code primaryColor}
   * @return JSON representation of the stored branding
   */
  @PUT
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update Tenant Branding",
      description =
          "Sets the primary colour for the authenticated tenant. Supported colours are blue, green,"
              + " purple, orange, red and yellow.\n\n"
              + "Example Request:\n\n"
              + "branding")
  @ApiResponse(responseCode = "200", description = "OK")
  public String updateBranding(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasPermissionTo(UPDATE_PERMISSION);

    // Parsed through FromJsonHelper rather than JsonCommand.from(String): that
    // factory leaves the command's own helper null, so reading a parameter off
    // it throws a NullPointerException.
    final String primaryColor =
        fromApiJsonHelper.extractStringNamed(
            "primaryColor", fromApiJsonHelper.parse(apiRequestBodyAsJson));
    final TenantBrandingData branding =
        tenantBrandingService.updateCurrentTenantBranding(primaryColor);

    return toApiJsonSerializer.serialize(branding);
  }
}
