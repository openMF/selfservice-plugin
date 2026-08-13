/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.selfservice.useradministration.data.AppSelfServiceUserData;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceUserAdminWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfServiceUserAdminApiResourceTest {

  private PlatformSecurityContext context;
  private AppUser staffUser;
  private AppSelfServiceUserReadPlatformService readPlatformService;
  private ToApiJsonSerializer<CommandProcessingResult> serializer;
  private SelfServiceUserAdminApiResource resource;

  @BeforeEach
  void setUp() {
    context = mock(PlatformSecurityContext.class);
    staffUser = mock(AppUser.class);
    readPlatformService = mock(AppSelfServiceUserReadPlatformService.class);
    SelfServiceUserAdminWritePlatformService writePlatformService =
        mock(SelfServiceUserAdminWritePlatformService.class);
    serializer = mock(ToApiJsonSerializer.class);
    when(context.authenticatedUser()).thenReturn(staffUser);
    resource =
        new SelfServiceUserAdminApiResource(
            context, readPlatformService, writePlatformService, serializer);
  }

  @Test
  void retrieveAll_shouldRequireReadPermissionAndSerializeUsers() {
    List<AppSelfServiceUserData> users =
        List.of(
            AppSelfServiceUserData.adminInstance(
                10L, "reader", null, null, null, null, null, null, true, false, null, null, null,
                null, true));
    when(readPlatformService.retrieveAllSelfServiceUsersForAdmin()).thenReturn(users);
    when(serializer.serialize(users)).thenReturn("users-json");

    String result = resource.retrieveAll();

    assertEquals("users-json", result);
    verify(staffUser).validateHasReadPermission("SELFSERVICEUSER");
    verify(readPlatformService).retrieveAllSelfServiceUsersForAdmin();
  }

  @Test
  void retrieveOne_shouldRequireReadPermissionAndSerializeUser() {
    AppSelfServiceUserData user =
        AppSelfServiceUserData.adminInstance(
            10L, "reader", null, null, null, null, null, null, true, false, null, null, null, null,
            true);
    when(readPlatformService.retrieveSelfServiceUserForAdmin(10L)).thenReturn(user);
    when(serializer.serialize(user)).thenReturn("user-json");

    String result = resource.retrieveOne(10L);

    assertEquals("user-json", result);
    verify(staffUser).validateHasReadPermission("SELFSERVICEUSER");
    verify(readPlatformService).retrieveSelfServiceUserForAdmin(10L);
  }
}
