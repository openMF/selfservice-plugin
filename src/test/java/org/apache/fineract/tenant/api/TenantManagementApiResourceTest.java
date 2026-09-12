/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.data.TenantManagementDataValidator;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.service.TenantManagementReadService;
import org.apache.fineract.tenant.service.TenantManagementWriteService;
import org.apache.fineract.tenant.service.TenantProvisioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantManagementApiResourceTest {

  private static final TenantData A_TENANT =
      new TenantData(
          1L,
          "acme",
          "Acme",
          "Asia/Kolkata",
          TenantStatus.ACTIVE,
          null,
          null,
          null,
          null,
          null,
          null);

  private static final String CREATE_JSON =
      """
      {
        "identifier": "acme", "name": "Acme", "timezoneId": "Asia/Kolkata",
        "schemaName": "acme", "schemaServer": "db", "schemaServerPort": "5432",
        "schemaUsername": "u", "schemaPassword": "p"
      }
      """;

  private static final String CONNECTION_JSON =
      """
      {
        "schemaName": "acme", "schemaServer": "db", "schemaServerPort": "5432",
        "schemaUsername": "u", "schemaPassword": "p"
      }
      """;

  private TenantManagementReadService readService;
  private TenantManagementWriteService writeService;
  private TenantProvisioningService provisioningService;
  private TenantManagementApiResource resource;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    readService = mock(TenantManagementReadService.class);
    writeService = mock(TenantManagementWriteService.class);
    provisioningService = mock(TenantProvisioningService.class);
    when(readService.retrieveAll(any(), any(), any(), any()))
        .thenReturn(new Page<>(List.of(A_TENANT), 1));
    when(readService.retrieveOne(anyLong())).thenReturn(A_TENANT);
    when(writeService.create(any())).thenReturn(A_TENANT);
    when(writeService.update(anyLong(), any())).thenReturn(A_TENANT);
    when(writeService.changeStatus(anyLong(), any())).thenReturn(A_TENANT);

    resource =
        new TenantManagementApiResource(
            readService,
            writeService,
            provisioningService,
            // A real validator, so the resource's own parsing is exercised rather
            // than stubbed away.
            new TenantManagementDataValidator(new FromJsonHelper()),
            mock(DefaultToApiJsonSerializer.class));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticateAs(final String username, final String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                null,
                Arrays.stream(roles)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList()));
  }

  // ---------------------------------------------------------------
  // Every endpoint requires the super master role, independently of the chain
  // ---------------------------------------------------------------

  @Test
  void everyEndpointServesASuperMaster() {
    authenticateAs("master", "SUPER_MASTER");

    resource.retrieveAll(null, null, null, null);
    resource.retrieveTemplate();
    resource.retrieveOne(1L);
    resource.create(CREATE_JSON);
    resource.update(1L, "{\"name\": \"Renamed\"}");
    resource.changeStatus(1L, "suspend");
    resource.delete(1L);
    resource.testConnection(CONNECTION_JSON);

    verify(writeService).create(any());
    verify(writeService).delete(1L);
  }

  @Test
  void anUnauthenticatedCallIsRefusedBeforeAnyWork() {
    assertThrows(
        NoAuthorizationException.class, () -> resource.retrieveAll(null, null, null, null));
    assertThrows(NoAuthorizationException.class, () -> resource.create(CREATE_JSON));

    verify(readService, never()).retrieveAll(any(), any(), any(), any());
    verify(writeService, never()).create(any());
  }

  @Test
  void anAuthenticatedUserWithoutTheSuperMasterRoleIsRefused() {
    // A tenant user who reached this code by any route - even one holding every
    // permission inside their own tenant - is not a master user.
    authenticateAs("mifos", "ALL_FUNCTIONS");

    assertThrows(NoAuthorizationException.class, () -> resource.retrieveTemplate());
    assertThrows(NoAuthorizationException.class, () -> resource.retrieveOne(1L));
    assertThrows(NoAuthorizationException.class, () -> resource.update(1L, "{\"name\": \"x\"}"));
    assertThrows(NoAuthorizationException.class, () -> resource.changeStatus(1L, "suspend"));
    assertThrows(NoAuthorizationException.class, () -> resource.delete(1L));
    assertThrows(NoAuthorizationException.class, () -> resource.testConnection(CONNECTION_JSON));

    verify(writeService, never()).delete(anyLong());
    verify(writeService, never()).changeStatus(anyLong(), any());
  }

  // ---------------------------------------------------------------
  // Status commands and filters
  // ---------------------------------------------------------------

  @Test
  void changeStatus_mapsEachCommandToItsStatus() {
    authenticateAs("master", "SUPER_MASTER");

    resource.changeStatus(1L, "activate");
    resource.changeStatus(1L, "deactivate");
    resource.changeStatus(1L, "suspend");

    verify(writeService).changeStatus(1L, TenantStatus.ACTIVE);
    verify(writeService).changeStatus(1L, TenantStatus.INACTIVE);
    verify(writeService).changeStatus(1L, TenantStatus.SUSPENDED);
  }

  @Test
  void changeStatus_rejectsAnUnknownCommand() {
    authenticateAs("master", "SUPER_MASTER");

    assertThrows(
        UnrecognizedQueryParamException.class, () -> resource.changeStatus(1L, "obliterate"));

    verify(writeService, never()).changeStatus(anyLong(), any());
  }

  @Test
  void changeStatus_rejectsAMissingCommandRatherThanGuessing() {
    authenticateAs("master", "SUPER_MASTER");

    assertThrows(UnrecognizedQueryParamException.class, () -> resource.changeStatus(1L, null));
  }

  @Test
  void list_rejectsAnUnknownStatusFilter() {
    authenticateAs("master", "SUPER_MASTER");

    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> resource.retrieveAll(null, "DELETED", null, null));
  }

  @Test
  void list_treatsABlankStatusFilterAsNoFilter() {
    authenticateAs("master", "SUPER_MASTER");

    resource.retrieveAll(null, "  ", null, null);

    verify(readService).retrieveAll(null, null, null, null);
  }
}
