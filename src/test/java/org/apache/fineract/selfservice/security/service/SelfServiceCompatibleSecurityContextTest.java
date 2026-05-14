/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.exception.UnAuthenticatedUserException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SelfServiceCompatibleSecurityContextTest {

  private ConfigurationDomainService config;
  private SelfServiceCompatibleSecurityContext ctx;
  private Office office;

  @BeforeEach
  void setUp() {
    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);

    config = mock(ConfigurationDomainService.class);
    ctx = new SelfServiceCompatibleSecurityContext(config);

    office = mock(Office.class);
    when(office.getId()).thenReturn(1L);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    ThreadLocalContextUtil.clearTenant();
  }

  private AppSelfServiceUser mockSelfServiceUser(long id, String username) {
    AppSelfServiceUser principal = mock(AppSelfServiceUser.class);
    when(principal.getId()).thenReturn(id);
    when(principal.getOffice()).thenReturn(office);
    when(principal.getUsername()).thenReturn(username);
    when(principal.getPassword()).thenReturn("secret");
    when(principal.isEnabled()).thenReturn(true);
    when(principal.isAccountNonExpired()).thenReturn(true);
    when(principal.isCredentialsNonExpired()).thenReturn(true);
    when(principal.isAccountNonLocked()).thenReturn(true);
    when(principal.getAuthorities()).thenReturn(new ArrayList<>());
    when(principal.getRoles()).thenReturn(new HashSet<>());
    when(principal.getEmail()).thenReturn(username + "@test.com");
    when(principal.getFirstname()).thenReturn("First");
    when(principal.getLastname()).thenReturn("Last");
    return principal;
  }

  private void setSecurityPrincipal(Object principal) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
  }

  private CommandWrapper beneficiaryCommandWrapper() {
    CommandWrapper cw = mock(CommandWrapper.class);
    when(cw.actionName()).thenReturn("CREATE");
    when(cw.getEntityName()).thenReturn("SSBENEFICIARYTPT");
    when(cw.getEntityId()).thenReturn(null);
    return cw;
  }

  // ── authenticatedUser() ──────────────────────────────────────────────────

  /** Tests that a valid self-service principal returns an AppUser stub. */
  @Test
  void authenticatedUser_selfServicePrincipal_returnsStub() {
    AppSelfServiceUser principal = mockSelfServiceUser(42L, "ssuser");
    setSecurityPrincipal(principal);

    AppUser stub = ctx.authenticatedUser();

    assertThat(stub.getId()).isEqualTo(42L);
    assertThat(stub.getUsername()).isEqualTo("ssuser");
    assertThat(stub.getOffice()).isSameAs(office);
    assertThat(stub.getEmail()).isEqualTo("ssuser@test.com");
    assertThat(stub.getFirstname()).isEqualTo("First");
    assertThat(stub.getLastname()).isEqualTo("Last");
  }

  /** Tests that missing principal throws UnAuthenticatedUserException. */
  @Test
  void authenticatedUser_noPrincipal_throwsUnAuthenticatedUserException() {
    assertThatThrownBy(() -> ctx.authenticatedUser())
        .isInstanceOf(UnAuthenticatedUserException.class);
  }

  // ── authenticatedUser(CommandWrapper) ────────────────────────────────────

  /** Tests context resolution with a command wrapper for self-service users. */
  @Test
  void authenticatedUserWithCommandWrapper_selfServicePrincipal_returnsStubWithCorrectFields() {
    AppSelfServiceUser principal = mockSelfServiceUser(99L, "ssuser3");
    setSecurityPrincipal(principal);

    AppUser stub = ctx.authenticatedUser(beneficiaryCommandWrapper());

    assertThat(stub.getId()).isEqualTo(99L);
    assertThat(stub.getUsername()).isEqualTo("ssuser3");
    assertThat(stub.getOffice()).isSameAs(office);
    assertThat(stub.getEmail()).isEqualTo("ssuser3@test.com");
    assertThat(stub.getFirstname()).isEqualTo("First");
    assertThat(stub.getLastname()).isEqualTo("Last");
  }

  /** Tests command wrapper resolution throws when no principal is found. */
  @Test
  void authenticatedUserWithCommandWrapper_noPrincipal_throwsUnAuthenticatedUserException() {
    assertThatThrownBy(() -> ctx.authenticatedUser(beneficiaryCommandWrapper()))
        .isInstanceOf(UnAuthenticatedUserException.class);
  }

  /**
   * Tests that password-expired self-service users are returned without throwing
   * ResetPasswordException.
   */
  @Test
  void
      authenticatedUserWithCommandWrapper_passwordExpiredSelfServiceUser_returnsStubWithoutThrowingResetPasswordException() {
    AppSelfServiceUser principal = mockSelfServiceUser(55L, "expireduser");
    when(principal.isPasswordResetRequired()).thenReturn(true);
    setSecurityPrincipal(principal);

    AppUser stub = ctx.authenticatedUser(beneficiaryCommandWrapper());

    assertThat(stub.getId()).isEqualTo(55L);
  }

  // ── getAuthenticatedUserIfPresent() ──────────────────────────────────────

  /** Tests getAuthenticatedUserIfPresent returns a stub for self-service user. */
  @Test
  void getAuthenticatedUserIfPresent_selfServicePrincipal_returnsStub() {
    AppSelfServiceUser principal = mockSelfServiceUser(7L, "ssuser2");
    setSecurityPrincipal(principal);

    AppUser stub = ctx.getAuthenticatedUserIfPresent();

    assertThat(stub.getId()).isEqualTo(7L);
    assertThat(stub.getUsername()).isEqualTo("ssuser2");
    assertThat(stub.getOffice()).isSameAs(office);
  }

  /** Tests getAuthenticatedUserIfPresent returns null when absent. */
  @Test
  void getAuthenticatedUserIfPresent_noPrincipal_returnsNull() {
    AppUser result = ctx.getAuthenticatedUserIfPresent();

    assertThat(result).isNull();
  }
}
