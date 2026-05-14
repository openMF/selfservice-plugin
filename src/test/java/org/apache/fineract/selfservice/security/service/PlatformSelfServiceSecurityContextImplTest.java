/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.apache.fineract.useradministration.exception.UnAuthenticatedUserException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

@ExtendWith(MockitoExtension.class)
class PlatformSelfServiceSecurityContextImplTest {

  @Mock private ConfigurationDomainService configurationDomainService;

  private PlatformSelfServiceSecurityContextImpl context;

  @BeforeEach
  void setUp() {
    context = new PlatformSelfServiceSecurityContextImpl(configurationDomainService);

    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);

    lenient().when(configurationDomainService.isPasswordForcedResetEnable()).thenReturn(false);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    ThreadLocalContextUtil.clearTenant();
  }

  private AppSelfServiceUser createAndSetSelfServiceUser(Set<Role> roles) {
    Office office = mock(Office.class);
    lenient().when(office.getId()).thenReturn(1L);
    lenient().when(office.getHierarchy()).thenReturn(".");

    Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
    for (Role role : roles) {
      for (Permission perm : role.getPermissions()) {
        authorities.add(new SimpleGrantedAuthority(perm.getCode()));
      }
    }
    if (authorities.isEmpty()) {
      authorities.add(new SimpleGrantedAuthority("DUMMY_ROLE"));
    }

    User springUser = new User("ssuser", "password123", true, true, true, true, authorities);
    AppSelfServiceUser ssUser =
        new AppSelfServiceUser(
            office,
            springUser,
            roles,
            "ss@test.com",
            "Self",
            "User",
            null,
            true,
            true,
            new ArrayList<Client>(),
            false);

    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(ssUser, null, ssUser.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(auth);

    return ssUser;
  }

  private void setAuthenticatedPrincipal(AppSelfServiceUser principal) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  // --- authenticatedSelfServiceUser ---

  @Test
  void authenticatedSelfServiceUser_shouldReturnUserWhenPrincipalIsSet() {
    AppSelfServiceUser ssUser = createAndSetSelfServiceUser(new HashSet<>());
    AppSelfServiceUser result = context.authenticatedSelfServiceUser();
    assertThat(result).isSameAs(ssUser);
  }

  @Test
  void authenticatedSelfServiceUser_shouldThrowWhenNoPrincipal() {
    assertThatThrownBy(() -> context.authenticatedSelfServiceUser())
        .isInstanceOf(UnAuthenticatedUserException.class);
  }

  @Test
  void authenticatedSelfServiceUser_shouldThrowWhenPrincipalIsNotSelfServiceUser() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("anonymous", null);
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> context.authenticatedSelfServiceUser())
        .isInstanceOf(UnAuthenticatedUserException.class);
  }

  // --- validateHasReadPermission ---

  @Test
  void validateHasReadPermission_shouldDelegateToSelfServiceUser() {
    AppSelfServiceUser principal = mock(AppSelfServiceUser.class);
    when(principal.getAuthorities()).thenReturn(new ArrayList<>());
    setAuthenticatedPrincipal(principal);
    context.validateHasReadPermission("CLIENT");
    verify(principal).validateHasSelfServiceReadPermission("CLIENT");
  }

  @Test
  void validateHasReadPermission_throwsWhenPrincipalIsNotSelfServiceUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("mifos", null));

    assertThrows(
        UnAuthenticatedUserException.class,
        () -> context.validateHasReadPermission("SAVINGSPRODUCT"));
  }

  // --- validateHasCreatePermission ---

  @Test
  void validateHasCreatePermission_shouldDelegateToSelfServiceUser() {
    AppSelfServiceUser principal = mock(AppSelfServiceUser.class);
    when(principal.getAuthorities()).thenReturn(new ArrayList<>());
    setAuthenticatedPrincipal(principal);
    context.validateHasCreatePermission("CLIENTIMAGE");
    verify(principal).validateHasCreatePermission("CLIENTIMAGE");
  }

  // --- validateHasDeletePermission ---

  @Test
  void validateHasDeletePermission_shouldDelegateToSelfServiceUser() {
    AppSelfServiceUser principal = mock(AppSelfServiceUser.class);
    when(principal.getAuthorities()).thenReturn(new ArrayList<>());
    setAuthenticatedPrincipal(principal);
    context.validateHasDeletePermission("CLIENTIMAGE");
    verify(principal).validateHasDeletePermission("CLIENTIMAGE");
  }

  // --- isAuthenticated ---

  @Test
  void isAuthenticated_shouldNotThrowWhenSelfServiceUserIsPresent() {
    createAndSetSelfServiceUser(new HashSet<>());
    context.isAuthenticated();
  }

  @Test
  void isAuthenticated_shouldThrowWhenNoPrincipal() {
    assertThatThrownBy(() -> context.isAuthenticated())
        .isInstanceOf(UnAuthenticatedUserException.class);
  }

  // --- getAuthenticatedUserIfPresent ---

  @Test
  void getAuthenticatedUserIfPresent_shouldReturnUserWhenPresent() {
    AppSelfServiceUser ssUser = createAndSetSelfServiceUser(new HashSet<>());
    AppSelfServiceUser result = context.getAuthenticatedUserIfPresent();
    assertThat(result).isSameAs(ssUser);
  }

  @Test
  void getAuthenticatedUserIfPresent_shouldReturnNullWhenNoPrincipal() {
    AppSelfServiceUser result = context.getAuthenticatedUserIfPresent();
    assertThat(result).isNull();
  }

  // --- validateAccessRights ---

  @Test
  void validateAccessRights_shouldPassWhenHierarchyMatches() {
    createAndSetSelfServiceUser(new HashSet<>());
    context.validateAccessRights(".");
  }

  // --- officeHierarchy ---

  @Test
  void officeHierarchy_shouldReturnUserOfficeHierarchy() {
    createAndSetSelfServiceUser(new HashSet<>());
    assertThat(context.officeHierarchy()).isEqualTo(".");
  }
}
