/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.permissions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

class AppSelfServiceUserExplicitReadPermissionTest {

  @BeforeEach
  void setUp() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null));
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void validateHasReadPermission_requiresExplicitReadGrant_evenIfAllFunctionsPresent() {
    AppSelfServiceUser user =
        newUserWithRolePermissions(
            Set.of("ALL_FUNCTIONS" /* intentionally missing READ_SAVINGSPRODUCT */));

    assertThrows(
        NoAuthorizationException.class,
        () -> user.validateHasSelfServiceReadPermission("SAVINGSPRODUCT"));
  }

  @Test
  void validateHasReadPermission_allowsWhenExplicitReadGrantPresent() {
    AppSelfServiceUser user = newUserWithRolePermissions(Set.of("READ_SAVINGSPRODUCT"));

    assertDoesNotThrow(() -> user.validateHasSelfServiceReadPermission("SAVINGSPRODUCT"));
  }

  private static AppSelfServiceUser newUserWithRolePermissions(Set<String> permissionCodes) {
    Office office = mock(Office.class);
    Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("DUMMY"));
    User springUser = new User("ssuser", "password", authorities);

    Role role = mock(Role.class);
    when(role.hasPermissionTo(anyString()))
        .thenAnswer(inv -> permissionCodes.contains(inv.getArgument(0, String.class)));

    return new AppSelfServiceUser(
        office,
        springUser,
        Set.of(role),
        "ss@test.local",
        "Self",
        "Service",
        null,
        true,
        true,
        new ArrayList<>(),
        false);
  }
}
