/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.service.TenantStatusLookupService;
import org.apache.fineract.tenant.service.TenantStatusLookupService.Lookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantStatusEnforcementFilterTest {

  private TenantStatusLookupService lookupService;
  private TenantStatusEnforcementFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private FilterChain chain;

  @BeforeEach
  void setUp() {
    lookupService = mock(TenantStatusLookupService.class);
    filter = new TenantStatusEnforcementFilter(lookupService);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    chain = mock(FilterChain.class);
  }

  private void addressedTo(final String tenant) {
    request.addHeader(TenantStatusEnforcementFilter.TENANT_ID_REQUEST_HEADER, tenant);
  }

  @Test
  void anActiveTenantPassesThrough() throws Exception {
    addressedTo("acme");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.known(TenantStatus.ACTIVE));

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertEquals(200, response.getStatus());
  }

  @ParameterizedTest
  @EnumSource(
      value = TenantStatus.class,
      names = {"INACTIVE", "SUSPENDED"})
  void aTenantThatIsNotActiveIsRefused(final TenantStatus status) throws Exception {
    // Without this the status column would be decorative: core resolves a tenant
    // with no status predicate, so a suspended tenant would keep being served.
    addressedTo("acme");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.known(status));

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertEquals(503, response.getStatus());
    assertTrue(response.getContentAsString().contains(status.name()));
  }

  @Test
  void aTenantWithAnUnrecognisedStoredStatusIsRefused() throws Exception {
    // Fails closed: an unrecognised value is a hand edit or corruption, and must
    // not be treated as ACTIVE.
    addressedTo("acme");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.unrecognised());

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertEquals(503, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNRECOGNISED"));
  }

  @Test
  void aRefusalHappensBeforeAnyCredentialIsRead() throws Exception {
    // The filter sits ahead of the security chain, so a suspended tenant is turned
    // away without the request ever reaching authentication.
    addressedTo("acme");
    request.addHeader("Authorization", "Basic bWlmb3M6cGFzc3dvcmQ=");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.known(TenantStatus.SUSPENDED));

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertFalse(response.getContentAsString().contains("bWlmb3M6cGFzc3dvcmQ="));
    assertFalse(response.getContentAsString().toLowerCase().contains("authorization"));
  }

  @Test
  void theDefaultTenantIsRefusedWhenSuspendedLikeAnyOther() throws Exception {
    // No tenant is exempt any more: administrators work in the master context, so
    // suspending "default" cannot lock them out of reinstating it.
    addressedTo("default");
    when(lookupService.statusOf("default")).thenReturn(Lookup.known(TenantStatus.SUSPENDED));

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertEquals(503, response.getStatus());
  }

  @Test
  void tenantAdministrationIsNeverBlockedEvenWithASuspendedTenantHeader() throws Exception {
    // A browser client attaches its tenant header to every request. Tenant management
    // is not addressed to that tenant, so a suspension must not reach it.
    request.setContextPath("/fineract-provider");
    request.setRequestURI("/fineract-provider/api/v1/admin/tenants/7");
    addressedTo("default");
    when(lookupService.statusOf("default")).thenReturn(Lookup.known(TenantStatus.SUSPENDED));

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(lookupService, never()).statusOf(any());
  }

  @Test
  void onlyTheTenantAdministrationPathsAreTreatedAsAdministration() {
    final org.springframework.mock.web.MockHttpServletRequest lookalike =
        new org.springframework.mock.web.MockHttpServletRequest();
    lookalike.setContextPath("/fineract-provider");
    lookalike.setRequestURI("/fineract-provider/api/v1/admin/tenantsx");
    assertFalse(TenantStatusEnforcementFilter.isTenantAdministration(lookalike));

    lookalike.setRequestURI("/fineract-provider/api/v1/admin/tenants");
    assertTrue(TenantStatusEnforcementFilter.isTenantAdministration(lookalike));

    lookalike.setRequestURI("/fineract-provider/v1/admin/tenants/template");
    assertTrue(TenantStatusEnforcementFilter.isTenantAdministration(lookalike));

    // Core's own OIDC configuration lives under the /v1/tenants namespace and belongs to
    // Fineract's chain, not to tenant administration.
    lookalike.setRequestURI("/fineract-provider/api/v1/tenants/default/oidc-config");
    assertFalse(TenantStatusEnforcementFilter.isTenantAdministration(lookalike));

    lookalike.setRequestURI("/fineract-provider/api/v1/offices");
    assertFalse(TenantStatusEnforcementFilter.isTenantAdministration(lookalike));
  }

  @Test
  void anUnknownTenantIsLeftToThePlatformToReject() throws Exception {
    // Producing our own error here would pre-empt - and disagree with - the
    // platform's InvalidTenantIdentifierException.
    addressedTo("never-existed");
    when(lookupService.statusOf("never-existed")).thenReturn(Lookup.noSuchTenant());

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  void anUnverifiableTenantIsRefusedWhileTheRegistryIsDown() throws Exception {
    // With no earlier status to fall back on, the request cannot be proved allowed.
    addressedTo("acme");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.registryUnavailable());

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertEquals(503, response.getStatus());
    assertTrue(response.getContentAsString().contains("\"tenantStatus\":\"UNAVAILABLE\""));
  }

  @Test
  void aRequestWithNoTenantIsLeftAlone() throws Exception {
    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(lookupService, never()).statusOf(any());
  }

  @Test
  void theTenantIsAlsoAcceptedFromTheQueryParameterAsCoreDoes() throws Exception {
    // TenantAwareBasicAuthenticationFilter falls back to this parameter, so a
    // request that reaches a tenant that way must be checked the same.
    request.setParameter(TenantStatusEnforcementFilter.TENANT_ID_REQUEST_PARAMETER, "acme");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.known(TenantStatus.SUSPENDED));

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(any(), any());
    assertEquals(503, response.getStatus());
  }

  @Test
  void theHeaderWinsOverTheQueryParameter() throws Exception {
    // Matching core's precedence, so the filter and the platform can never be
    // looking at two different tenants for the same request.
    addressedTo("acme");
    request.setParameter(TenantStatusEnforcementFilter.TENANT_ID_REQUEST_PARAMETER, "other");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.known(TenantStatus.ACTIVE));

    filter.doFilter(request, response, chain);

    verify(lookupService).statusOf("acme");
    verify(lookupService, never()).statusOf("other");
  }

  @Test
  void surroundingWhitespaceInTheHeaderIsIgnored() throws Exception {
    addressedTo("  acme  ");
    when(lookupService.statusOf("acme")).thenReturn(Lookup.known(TenantStatus.ACTIVE));

    filter.doFilter(request, response, chain);

    verify(lookupService).statusOf("acme");
  }
}
