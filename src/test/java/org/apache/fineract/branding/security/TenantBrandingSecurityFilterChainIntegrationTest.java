/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Guards the security wiring of the tenant branding endpoint.
 *
 * <p>Branding is the one resource in this plugin outside the {@code /v1/self/} prefix, which left
 * it belonging to neither of the chains that would otherwise claim it. Two failures followed, and
 * both are invisible from the resource itself because they happen in the filter chain before it
 * runs:
 *
 * <ul>
 *   <li>the self-service chain claiming the request and authenticating a platform user against the
 *       self-service user store, where that user does not exist - 401 despite valid credentials
 *   <li>no chain claiming it at all, which skips authentication and, because the same filter
 *       resolves the tenant, leaves the tenant null so every tenant reads the default colour
 * </ul>
 *
 * <p>{@code TenantBrandingSecurityConfiguration} claims both spellings ahead of both chains. These
 * tests hold it there.
 *
 * <p>Chains are interrogated directly where the question is which one claims a path, because the
 * resource is JAX-RS and is not registered with the Spring MVC dispatcher: under MockMvc every path
 * answers 404 regardless of which filters ran. Authorization is asserted through MockMvc, which
 * does work, because Spring Security decides before the dispatcher is reached - so a rejected
 * request answers 401 while a permitted one falls through to the dispatcher's 404.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = TenantBrandingSecurityTestConfig.class,
    properties = "spring.main.allow-bean-definition-overriding=true")
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
@AutoConfigureMockMvc
class TenantBrandingSecurityFilterChainIntegrationTest {

  private static final String BRANDING_PATH = "/v1/branding";
  private static final String PREFIXED_BRANDING_PATH = "/api/v1/branding";
  private static final String PREFIXED_SELF_PATH = "/api/v1/self/clients";
  private static final String BARE_SELF_PATH = "/v1/self/clients";
  private static final String TENANT_HEADER = "Fineract-Platform-TenantId";

  @Autowired private MockMvc mockMvc;
  @Autowired private List<SecurityFilterChain> filterChains;

  @Autowired
  @Qualifier("selfServiceSecurityFilterChain") private SecurityFilterChain selfServiceFilterChain;

  @Autowired
  @Qualifier("tenantBrandingSecurityFilterChain") private SecurityFilterChain brandingFilterChain;

  @MockitoBean private SelfServiceAuthenticationTokenService selfServiceAuthenticationTokenService;

  @Test
  @DisplayName("branding is claimed under both spellings, so protection does not depend on prefix")
  void brandingPaths_areClaimedUnderBothSpellings() {
    assertTrue(
        isClaimedByAnyChain(PREFIXED_BRANDING_PATH),
        () -> PREFIXED_BRANDING_PATH + " is claimed by no SecurityFilterChain");
    assertTrue(
        isClaimedByAnyChain(BRANDING_PATH),
        () ->
            BRANDING_PATH
                + " is claimed by no SecurityFilterChain, so a client resolving branding without"
                + " the /api prefix reaches it with neither authentication nor a tenant.");
  }

  /**
   * Ordering is the whole point: the self-service chain would otherwise claim these paths first in
   * deployments where its matchers resolve differently, and reject platform users.
   */
  @Test
  @DisplayName("the branding chain, not the self-service chain, claims branding first")
  void brandingChain_winsAheadOfTheSelfServiceChain() {
    assertSame(
        brandingFilterChain,
        firstChainClaiming(PREFIXED_BRANDING_PATH),
        "the branding chain must be evaluated before any other chain claiming "
            + PREFIXED_BRANDING_PATH);
    assertSame(
        brandingFilterChain,
        firstChainClaiming(BRANDING_PATH),
        "the branding chain must be evaluated before any other chain claiming " + BRANDING_PATH);
  }

  /**
   * The self-service chain authenticates against the self-service user store, so a platform user
   * can never satisfy it.
   *
   * <p>The chains name themselves in the challenge they emit: the self-service chain answers {@code
   * WWW-Authenticate: Basic realm="Fineract Self Service API"}, the platform entry point used here
   * and by Fineract's main chain answers {@code realm="Fineract Platform API"}. A branding 401
   * carrying the former is the self-service chain having claimed the request.
   */
  @Test
  @DisplayName("branding is not claimed by the self-service chain, which uses the wrong user store")
  void brandingPaths_areNotClaimedByTheSelfServiceChain() {
    assertFalse(
        selfServiceFilterChain.matches(requestFor(PREFIXED_BRANDING_PATH)),
        () ->
            PREFIXED_BRANDING_PATH
                + " is claimed by the self-service chain, which authenticates against the"
                + " self-service user store. Platform users do not exist there, so every branding"
                + " read is rejected with 401 before reaching the resource.");
    assertFalse(
        selfServiceFilterChain.matches(requestFor(BRANDING_PATH)),
        () -> BRANDING_PATH + " is claimed by the self-service chain");
  }

  /**
   * Claiming branding must not widen into anything else. Asserted as "the branding chain does not
   * claim self-service paths" rather than "the self-service chain claims them", because which chain
   * wins for {@code /api/v1/self/**} is decided by ordering this change does not touch.
   */
  @Test
  @DisplayName("the branding chain claims nothing beyond branding")
  void brandingChain_doesNotClaimAnythingElse() {
    assertFalse(
        brandingFilterChain.matches(requestFor(PREFIXED_SELF_PATH)),
        () -> "the branding chain claims " + PREFIXED_SELF_PATH);
    assertFalse(
        brandingFilterChain.matches(requestFor(BARE_SELF_PATH)),
        () -> "the branding chain claims " + BARE_SELF_PATH);
    assertFalse(
        brandingFilterChain.matches(requestFor("/api/v1/offices")),
        "the branding chain claims unrelated platform paths");
  }

  /**
   * Victor's requirement, asserted where it is actually decided. A permitted request is not
   * rejected by security; it reaches the dispatcher, which has no JAX-RS handler registered and
   * answers 404.
   */
  @Test
  @DisplayName("an anonymous read is permitted under both spellings")
  void anonymousRead_isPermitted() throws Exception {
    mockMvc
        .perform(get(PREFIXED_BRANDING_PATH).header(TENANT_HEADER, "default"))
        .andExpect(notRejected(PREFIXED_BRANDING_PATH));
    mockMvc
        .perform(get(BRANDING_PATH).header(TENANT_HEADER, "default"))
        .andExpect(notRejected(BRANDING_PATH));
  }

  /** The colour is tenant configuration: reading it is open, changing it is not. */
  @Test
  @DisplayName("an anonymous write is rejected under both spellings")
  void anonymousWrite_isRejected() throws Exception {
    mockMvc
        .perform(
            put(PREFIXED_BRANDING_PATH)
                .header(TENANT_HEADER, "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryColor\":\"green\"}"))
        .andExpect(rejected(PREFIXED_BRANDING_PATH));
    mockMvc
        .perform(
            put(BRANDING_PATH)
                .header(TENANT_HEADER, "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"primaryColor\":\"green\"}"))
        .andExpect(rejected(BRANDING_PATH));
  }

  /**
   * @param path path being exercised, for the failure message
   * @return matcher asserting security let the request through
   */
  private static ResultMatcher notRejected(final String path) {
    return result -> {
      final int status = result.getResponse().getStatus();
      if (status == 401 || status == 403) {
        throw new AssertionError(
            "An anonymous read of " + path + " was rejected with " + status + ".");
      }
    };
  }

  /**
   * @param path path being exercised, for the failure message
   * @return matcher asserting security rejected the request
   */
  private static ResultMatcher rejected(final String path) {
    return result -> {
      final int status = result.getResponse().getStatus();
      if (status != 401 && status != 403) {
        throw new AssertionError(
            "An anonymous write to "
                + path
                + " was answered with "
                + status
                + " rather than being rejected.");
      }
    };
  }

  /**
   * @param path servlet relative path to test
   * @return whether any configured chain claims the path
   */
  private boolean isClaimedByAnyChain(final String path) {
    return firstChainClaiming(path) != null;
  }

  /**
   * @param path servlet relative path to test
   * @return the chain Spring Security would use, which is the first one matching
   */
  private SecurityFilterChain firstChainClaiming(final String path) {
    final HttpServletRequest request = requestFor(path);
    return filterChains.stream().filter(chain -> chain.matches(request)).findFirst().orElse(null);
  }

  /**
   * Builds a request the path matchers can evaluate. {@code PathPatternRequestMatcher} reads the
   * parsed {@code RequestPath} rather than the raw URI, so it has to be cached on the request
   * first.
   *
   * @param path servlet relative path
   * @return request positioned at that path
   */
  private static HttpServletRequest requestFor(final String path) {
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    ServletRequestPathUtils.parseAndCache(request);
    return request;
  }
}
