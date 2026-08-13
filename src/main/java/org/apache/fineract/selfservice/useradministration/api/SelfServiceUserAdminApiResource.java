/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.selfservice.useradministration.data.AppSelfServiceUserData;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceUserAdminWritePlatformService;
import org.springframework.stereotype.Component;

/**
 * Administrative API for managing self-service users. Each operation requires the corresponding
 * {@code SELFSERVICEUSER} permission and service-layer office hierarchy checks.
 */
@Path("/v1/selfservice/users")
@Component
@Tag(name = "Self Service User Administration")
@RequiredArgsConstructor
public class SelfServiceUserAdminApiResource {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "SELFSERVICEUSER";

  private final PlatformSecurityContext context;
  private final AppSelfServiceUserReadPlatformService readPlatformService;
  private final SelfServiceUserAdminWritePlatformService writePlatformService;
  private final ToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;

  /**
   * Lists self-service users visible to the authenticated administrator.
   *
   * @return serialized self-service user list; requires {@code READ_SELFSERVICEUSER}
   */
  @GET
  @Produces({MediaType.APPLICATION_JSON})
  public String retrieveAll() {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return toApiJsonSerializer.serialize(readPlatformService.retrieveAllSelfServiceUsersForAdmin());
  }

  /**
   * Retrieves one self-service user visible to the authenticated administrator.
   *
   * @param userId self-service user identifier
   * @return serialized self-service user details; requires {@code READ_SELFSERVICEUSER}
   */
  @GET
  @Path("{userId}")
  @Produces({MediaType.APPLICATION_JSON})
  public String retrieveOne(@PathParam("userId") Long userId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    AppSelfServiceUserData user = readPlatformService.retrieveSelfServiceUserForAdmin(userId);
    return toApiJsonSerializer.serialize(user);
  }

  /**
   * Activates a self-service user visible to the authenticated administrator.
   *
   * @param userId self-service user identifier
   * @return serialized command result; requires {@code UPDATE_SELFSERVICEUSER}
   */
  @PUT
  @Path("{userId}/activate")
  @Produces({MediaType.APPLICATION_JSON})
  public String activate(@PathParam("userId") Long userId) {
    context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return toApiJsonSerializer.serialize(writePlatformService.activate(userId));
  }

  /**
   * Inactivates a self-service user visible to the authenticated administrator.
   *
   * @param userId self-service user identifier
   * @return serialized command result; requires {@code UPDATE_SELFSERVICEUSER}
   */
  @PUT
  @Path("{userId}/inactivate")
  @Produces({MediaType.APPLICATION_JSON})
  public String inactivate(@PathParam("userId") Long userId) {
    context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return toApiJsonSerializer.serialize(writePlatformService.inactivate(userId));
  }

  /**
   * Links a visible self-service user to a visible client.
   *
   * @param userId self-service user identifier
   * @param clientId client identifier
   * @return serialized command result; requires {@code UPDATE_SELFSERVICEUSER}
   */
  @PUT
  @Path("{userId}/clients/{clientId}")
  @Produces({MediaType.APPLICATION_JSON})
  public String linkClient(@PathParam("userId") Long userId, @PathParam("clientId") Long clientId) {
    context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return toApiJsonSerializer.serialize(writePlatformService.linkClient(userId, clientId));
  }

  /**
   * Removes an existing client link from a visible self-service user.
   *
   * @param userId self-service user identifier
   * @param clientId client identifier
   * @return serialized command result; requires {@code UPDATE_SELFSERVICEUSER}
   */
  @DELETE
  @Path("{userId}/clients/{clientId}")
  @Produces({MediaType.APPLICATION_JSON})
  public String delinkClient(
      @PathParam("userId") Long userId, @PathParam("clientId") Long clientId) {
    context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return toApiJsonSerializer.serialize(writePlatformService.delinkClient(userId, clientId));
  }

  /**
   * Soft-deletes a visible self-service user and releases its unique username.
   *
   * @param userId self-service user identifier
   * @return serialized command result; requires {@code DELETE_SELFSERVICEUSER}
   */
  @DELETE
  @Path("{userId}")
  @Produces({MediaType.APPLICATION_JSON})
  public String delete(@PathParam("userId") Long userId) {
    context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    return toApiJsonSerializer.serialize(writePlatformService.delete(userId));
  }
}
