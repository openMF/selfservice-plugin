/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.tenant.data.TenantConnectionTestRequest;
import org.apache.fineract.tenant.data.TenantCreateRequest;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.data.TenantManagementDataValidator;
import org.apache.fineract.tenant.data.TenantUpdateRequest;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.security.TenantMasterAccess;
import org.apache.fineract.tenant.service.TenantManagementReadService;
import org.apache.fineract.tenant.service.TenantManagementWriteService;
import org.apache.fineract.tenant.service.TenantProvisioningService;
import org.springframework.stereotype.Component;

/**
 * Administration of the tenants on this installation, under {@code /v1/admin/tenants}.
 *
 * <p>Served in the master context: requests authenticate as a master user from the tenant store and
 * must hold the {@code SUPER_MASTER} role - see {@code TenantManagementSecurityConfiguration}. No
 * tenant user, however privileged inside its own tenant, can call these endpoints, and no {@code
 * Fineract-Platform-TenantId} header is needed.
 *
 * <p>The chain enforces the role before a request arrives here. Each method checks it again through
 * {@link TenantMasterAccess#requireSuperMaster()}, so a mistake in the chain's path matching fails
 * closed instead of exposing tenant administration.
 */
@Path("/v1/admin/tenants")
@Component
@Tag(
    name = "Tenant Management",
    description =
        "Lifecycle management of the tenants on this installation. Requires a master user with the"
            + " SUPER_MASTER role; database credentials are write-only and are never returned.")
@RequiredArgsConstructor
public class TenantManagementApiResource {

  private static final String COMMAND_ACTIVATE = "activate";
  private static final String COMMAND_DEACTIVATE = "deactivate";
  private static final String COMMAND_SUSPEND = "suspend";

  private final TenantManagementReadService readService;
  private final TenantManagementWriteService writeService;
  private final TenantProvisioningService provisioningService;
  private final TenantManagementDataValidator validator;
  private final DefaultToApiJsonSerializer<TenantData> toApiJsonSerializer;

  /** Lists tenants, optionally filtered and paged. */
  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Tenants",
      description =
          "Returns the tenants on this installation, newest registry entries last.\n\n"
              + "Optional `search` matches the identifier and the name, case insensitively and"
              + " literally. Optional `status` restricts to ACTIVE, INACTIVE or SUSPENDED. `offset`"
              + " and `limit` page the result; `limit` is capped so one request cannot return an"
              + " entire large registry.\n\n"
              + "Database credentials are never included.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          TenantManagementApiResourceSwagger.GetTenantsResponse.class)))
  @ApiResponse(responseCode = "400", description = "Validation failed")
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  public String retrieveAll(
      @QueryParam("search")
          @Parameter(description = "Matches identifier and name, literally and case-insensitively")
          final String search,
      @QueryParam("status") @Parameter(description = "ACTIVE, INACTIVE or SUSPENDED")
          final String status,
      @QueryParam("offset") final Integer offset,
      @QueryParam("limit") final Integer limit) {

    TenantMasterAccess.requireSuperMaster();

    final TenantStatus statusFilter =
        status == null || status.isBlank()
            ? null
            : TenantStatus.fromString(status)
                .orElseThrow(
                    () ->
                        new UnrecognizedQueryParamException(
                            "status", status, TenantStatus.names().toArray()));

    final Page<TenantData> tenants = readService.retrieveAll(search, statusFilter, offset, limit);
    return toApiJsonSerializer.serialize(tenants);
  }

  /** Options an administration client needs to build its create and edit forms. */
  @GET
  @Path("/template")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Tenant Template",
      description =
          "Returns the selectable time zones and lifecycle statuses, so a client never hardcodes a"
              + " list the backend owns.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          TenantManagementApiResourceSwagger.GetTenantsTemplateResponse.class)))
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  public String retrieveTemplate() {
    TenantMasterAccess.requireSuperMaster();
    return toApiJsonSerializer.serialize(readService.retrieveTemplate());
  }

  /** Retrieves a single tenant. */
  @GET
  @Path("/{id}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a Tenant",
      description = "Returns one tenant. Database credentials are never included.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation = TenantManagementApiResourceSwagger.GetTenantResponse.class)))
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  @ApiResponse(responseCode = "404", description = "No tenant has this id")
  public String retrieveOne(@PathParam("id") final Long id) {
    TenantMasterAccess.requireSuperMaster();
    return toApiJsonSerializer.serialize(readService.retrieveOne(id));
  }

  /** Registers a new tenant and provisions its schema. */
  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create a Tenant",
      description =
          "Registers a tenant, creates its schema and migrates it.\n\n"
              + "The schema is created and proved reachable before anything is written to the"
              + " registry, so a tenant that could never have worked leaves no row behind. An"
              + " existing schema is reused rather than rejected, and is never emptied.\n\n"
              + "`schemaPassword` is stored encrypted and is never returned by any endpoint.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation = TenantManagementApiResourceSwagger.GetTenantResponse.class)))
  @ApiResponse(responseCode = "400", description = "Validation failed")
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  @ApiResponse(responseCode = "403", description = "Refused by a domain rule")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          TenantManagementApiResourceSwagger.PostTenantsRequest.class)))
  public String create(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    TenantMasterAccess.requireSuperMaster();
    final TenantCreateRequest request = validator.validateForCreate(apiRequestBodyAsJson);
    return toApiJsonSerializer.serialize(writeService.create(request));
  }

  /** Applies a partial update to a tenant. */
  @PUT
  @Path("/{id}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update a Tenant",
      description =
          "Updates a tenant. Every field is optional; omitting one leaves it unchanged, and"
              + " omitting `schemaPassword` keeps the stored credential.\n\n"
              + "`identifier` cannot be changed: it is how every request selects a tenant and is"
              + " embedded in that tenant's existing sessions and integrations. Sending one is"
              + " rejected rather than ignored.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation = TenantManagementApiResourceSwagger.GetTenantResponse.class)))
  @ApiResponse(responseCode = "400", description = "Validation failed")
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  @ApiResponse(responseCode = "403", description = "Refused by a domain rule")
  @ApiResponse(responseCode = "404", description = "No tenant has this id")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation = TenantManagementApiResourceSwagger.PutTenantsRequest.class)))
  public String update(
      @PathParam("id") final Long id, @Parameter(hidden = true) final String apiRequestBodyAsJson) {
    TenantMasterAccess.requireSuperMaster();
    final TenantUpdateRequest request = validator.validateForUpdate(apiRequestBodyAsJson);
    return toApiJsonSerializer.serialize(writeService.update(id, request));
  }

  /** Moves a tenant between lifecycle states. */
  @POST
  @Path("/{id}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Change Tenant Status",
      description =
          "Activates, deactivates or suspends a tenant, selected with the `command` query"
              + " parameter.\n\n"
              + "Idempotent: issuing a command a tenant is already in succeeds and changes"
              + " nothing, so a retried request does not look like a failure.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation = TenantManagementApiResourceSwagger.GetTenantResponse.class)))
  @ApiResponse(responseCode = "400", description = "Validation failed")
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  @ApiResponse(responseCode = "404", description = "No tenant has this id")
  public String changeStatus(
      @PathParam("id") final Long id,
      @QueryParam("command")
          @Parameter(description = "activate, deactivate or suspend", required = true)
          final String command) {

    TenantMasterAccess.requireSuperMaster();

    final TenantStatus target =
        switch (command == null ? "" : command) {
          case COMMAND_ACTIVATE -> TenantStatus.ACTIVE;
          case COMMAND_DEACTIVATE -> TenantStatus.INACTIVE;
          case COMMAND_SUSPEND -> TenantStatus.SUSPENDED;
          default ->
              throw new UnrecognizedQueryParamException(
                  "command", command, COMMAND_ACTIVATE, COMMAND_DEACTIVATE, COMMAND_SUSPEND);
        };

    return toApiJsonSerializer.serialize(writeService.changeStatus(id, target));
  }

  /** Removes a tenant from the registry, leaving its data intact. */
  @DELETE
  @Path("/{id}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Remove a Tenant",
      description =
          "Removes the tenant's registry entry so the platform stops routing to it.\n\n"
              + "This never drops a schema or deletes tenant data: the database is left intact for"
              + " retention, audit or reinstatement.\n\n"
              + "An active tenant is refused; deactivate it first, so removal is a deliberate"
              + " two-step action.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          TenantManagementApiResourceSwagger.DeleteTenantResponse.class)))
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  @ApiResponse(responseCode = "403", description = "Refused by a domain rule")
  @ApiResponse(responseCode = "404", description = "No tenant has this id")
  public String delete(@PathParam("id") final Long id) {
    TenantMasterAccess.requireSuperMaster();
    writeService.delete(id);
    return toApiJsonSerializer.serialize(Map.of("resourceId", id));
  }

  /** Probes a database without registering anything. */
  @POST
  @Path("/test-connection")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Test a Tenant Database Connection",
      description =
          "Opens a connection with the supplied details and reports whether it succeeded, so an"
              + " administrator can check credentials before committing a tenant.\n\n"
              + "Returns only whether the database answered. The driver's own error is written to"
              + " the server log rather than returned, since those messages routinely echo the"
              + " connection string and user back.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          TenantManagementApiResourceSwagger.PostTestConnectionResponse.class)))
  @ApiResponse(responseCode = "400", description = "Validation failed")
  @ApiResponse(responseCode = "401", description = "Not authenticated as a master user")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          TenantManagementApiResourceSwagger.PostTestConnectionRequest.class)))
  public String testConnection(@Parameter(hidden = true) final String apiRequestBodyAsJson) {
    TenantMasterAccess.requireSuperMaster();

    final TenantConnectionTestRequest request =
        validator.validateForConnectionTest(apiRequestBodyAsJson);

    final boolean reachable =
        provisioningService.isReachable(
            request.schemaServer(),
            request.schemaServerPort(),
            request.schemaName(),
            request.schemaConnectionParameters(),
            request.schemaUsername(),
            request.schemaPassword());

    return toApiJsonSerializer.serialize(Map.of("reachable", reachable));
  }
}
