/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.service.TenantStatusLookupService;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses requests addressed to a tenant that is not {@link TenantStatus#ACTIVE}.
 *
 * <p><strong>Why this exists.</strong> Storing a status achieves nothing on its own. Apache
 * Fineract resolves a tenant with {@code where t.identifier = ?} and no status predicate - in
 * {@code JdbcTenantDetailsService} and in {@code AuthTenantDetailsServiceJdbc}, the authentication
 * path - so without this filter a suspended tenant would keep authenticating and serving requests,
 * and MX-406's "tenant status changes correctly affect routing and availability" would be unmet.
 *
 * <p>Enforcing it here rather than in those core services is deliberate: MX-406 lists changes to
 * core multi-tenancy routing and authentication filters as a non-goal, and a plugin cannot alter
 * them in any case. A servlet filter sits in front of the whole chain and needs no core change.
 *
 * <p>Deliberately not a {@code @Component}: Spring Boot would auto-register it at an arbitrary
 * position. {@code TenantManagementConfig} registers it explicitly so that running ahead of the
 * security chain is a stated decision rather than an accident of bean discovery.
 *
 * <p>Runs before authentication, so a suspended tenant is turned away without any credential being
 * checked. The tenant is read from the request exactly as core reads it - the {@code
 * Fineract-Platform-TenantId} header, falling back to a {@code tenantIdentifier} query parameter -
 * so this filter and the platform always agree on which tenant a request is for.
 */
@Slf4j
public class TenantStatusEnforcementFilter extends OncePerRequestFilter {

  /**
   * Header naming the tenant. Matches core's {@code TenantAwareBasicAuthenticationFilter}.
   *
   * <p>Note MX-406's technical notes cite {@code X-Mifos-Platform-TenantId}; the platform renamed
   * this header and the ticket is out of date. The value here follows the running platform.
   */
  static final String TENANT_ID_REQUEST_HEADER = "Fineract-Platform-TenantId";

  /** Query-parameter fallback, also matching core. */
  static final String TENANT_ID_REQUEST_PARAMETER = "tenantIdentifier";

  private final TenantStatusLookupService statusLookupService;

  public TenantStatusEnforcementFilter(final TenantStatusLookupService statusLookupService) {
    this.statusLookupService = statusLookupService;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final String identifier = tenantIdentifierOf(request);

    // Tenant administration is not addressed to a tenant: it runs in the master context,
    // authenticated against master users rather than any tenant's. It is never blocked
    // here, so suspending any tenant - including the one a UI happens to send in its
    // header - cannot lock master users out of reinstating it.
    if (identifier == null || isTenantAdministration(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    final TenantStatusLookupService.Lookup lookup = statusLookupService.statusOf(identifier);

    // No such tenant is not this filter's decision to make: the platform's own tenant
    // resolution runs next and produces the proper error. An unrecognised status, or a
    // registry that cannot be read with no earlier status to fall back on, is refused -
    // see TenantStatusLookupService.Lookup#refusesService.
    if (!lookup.refusesService()) {
      filterChain.doFilter(request, response);
      return;
    }

    // The raw value of an unrecognised status is not echoed: it is whatever was written
    // into the registry by hand, and has no business in a response.
    final String reportedStatus =
        switch (lookup.kind()) {
          case KNOWN -> lookup.status().name();
          case REGISTRY_UNAVAILABLE -> "UNAVAILABLE";
          default -> "UNRECOGNISED";
        };
    log.info("Refusing request for tenant [{}] in status {}", identifier, reportedStatus);
    respondUnavailable(response, reportedStatus);
  }

  /**
   * @return true when the request targets {@code /v1/admin/tenants}, with or without the {@code
   *     /api} prefix, matching the paths {@code TenantManagementSecurityConfiguration} claims
   */
  static boolean isTenantAdministration(final HttpServletRequest request) {
    final String uri = request.getRequestURI();
    if (uri == null) {
      return false;
    }
    final String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    final String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
    return path.equals("/api/v1/admin/tenants")
        || path.startsWith("/api/v1/admin/tenants/")
        || path.equals("/v1/admin/tenants")
        || path.startsWith("/v1/admin/tenants/");
  }

  /** Reads the tenant the way core does, so the two cannot disagree about a request. */
  private static String tenantIdentifierOf(final HttpServletRequest request) {
    final String fromHeader = request.getHeader(TENANT_ID_REQUEST_HEADER);
    if (fromHeader != null && !fromHeader.isBlank()) {
      return fromHeader.trim();
    }
    final String fromParameter = request.getParameter(TENANT_ID_REQUEST_PARAMETER);
    return fromParameter == null || fromParameter.isBlank() ? null : fromParameter.trim();
  }

  /**
   * Answers 503.
   *
   * <p>Not 404: the tenant exists, and pretending otherwise would send an operator hunting for a
   * misconfiguration instead of seeing the state they themselves set. Not 401 or 403 either - the
   * caller's credentials were never in question and have not been looked at.
   *
   * <p>The body names the status and nothing else. No credential was read and none is mentioned.
   */
  private static void respondUnavailable(
      final HttpServletResponse response, final String reportedStatus) throws IOException {
    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            "{\"developerMessage\":\"The tenant addressed by this request is not active.\","
                + "\"httpStatusCode\":\"503\","
                + "\"defaultUserMessage\":\"This service is currently unavailable.\","
                + "\"userMessageGlobalisationCode\":\"error.msg.tenant.not.active\","
                + "\"tenantStatus\":\""
                + reportedStatus
                + "\"}");
  }
}
