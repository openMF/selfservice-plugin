/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.starter;

import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.CacheWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.data.PlatformRequestLog;
import org.apache.fineract.infrastructure.security.filter.TenantAwareBasicAuthenticationFilter;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.notification.service.UserNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security for the tenant branding endpoint.
 *
 * <p>Branding needs its own chain because it is the one resource in this plugin outside the {@code
 * /v1/self/} prefix, and so belongs to neither of the two chains that would otherwise claim it:
 *
 * <ul>
 *   <li>{@code SelfServiceSecurityConfiguration} authenticates against the self-service user store.
 *       A platform user does not exist there, so if that chain claims a branding request the caller
 *       is rejected with 401 despite presenting valid platform credentials - and the rejection
 *       happens in the filter chain, so nothing the resource does about its own authentication can
 *       prevent it.
 *   <li>Fineract's main chain claims {@code /api/**}, which covers branding only where the
 *       deployment resolves it under that prefix. A client resolving a bare {@code /v1/branding}
 *       reaches a path no chain claims, which skips authentication and - because {@code
 *       TenantAwareBasicAuthenticationFilter} is also what resolves the tenant - leaves the tenant
 *       null, so every tenant silently reads the default colour.
 * </ul>
 *
 * <p>Ordered ahead of the self-service chain so that neither outcome depends on which prefix a
 * deployment happens to use. Both spellings are claimed here, explicitly.
 *
 * <p>Reads are anonymous: the colour is not sensitive, every client needs it to render, and the
 * login screen has to paint the tenant's brand before anyone has authenticated. Writes still
 * require a platform user, and the resource additionally requires {@code UPDATE_CONFIGURATION} - a
 * Fineract permission check belongs with the handler rather than here.
 *
 * <p>The authentication manager is built explicitly from the platform provider rather than left to
 * {@code httpBasic()} to resolve. An implicitly resolved manager takes whichever {@code
 * UserDetailsService} the context offers, which in this application is ambiguous - and picking the
 * self-service one is precisely the failure this chain exists to prevent.
 */
@Configuration
public class TenantBrandingSecurityConfiguration {

  private static final PathPatternRequestMatcher.Builder API_MATCHER =
      PathPatternRequestMatcher.withDefaults();

  /** Branding as resolved by a client that includes the API prefix. */
  private static final String PREFIXED_BRANDING_PATH = "/api/v1/branding";

  /** Branding as resolved by a client that does not, mirroring the self-service chain. */
  private static final String BRANDING_PATH = "/v1/branding";

  @Autowired
  @Qualifier("customAuthenticationProvider") private DaoAuthenticationProvider platformAuthenticationProvider;

  @Autowired
  @Qualifier("basicAuthenticationEntryPoint") private BasicAuthenticationEntryPoint platformAuthenticationEntryPoint;

  @Autowired private ToApiJsonSerializer<PlatformRequestLog> toApiJsonSerializer;
  @Autowired private ConfigurationDomainService configurationDomainService;
  @Autowired private CacheWritePlatformService cacheWritePlatformService;
  @Autowired private UserNotificationService userNotificationService;
  @Autowired private AuthTenantDetailsService basicAuthTenantDetailsService;
  @Autowired private BusinessDateReadPlatformService businessDateReadPlatformService;
  @Autowired private FineractProperties fineractProperties;

  /**
   * The order belongs on the bean method rather than the class: a class level {@code @Order} does
   * not reach the {@code SecurityFilterChain} beans a configuration produces, so declaring it there
   * leaves the chain sorted by default precedence and silently claiming nothing.
   *
   * @param http security builder for this chain
   * @return chain claiming both spellings of the branding path
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Order(0)
  public SecurityFilterChain tenantBrandingSecurityFilterChain(final HttpSecurity http)
      throws Exception {

    http.securityMatcher(brandingRequestMatcher())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(smc -> smc.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // Resolves the tenant from the Fineract-Platform-TenantId header and authenticates
        // any credentials that are present. Anonymous requests pass through with the tenant
        // already set, which is what lets a read answer the right colour without a user.
        .addFilterBefore(brandingAuthenticationFilter(), SecurityContextHolderFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // Preflight carries no credentials.
                    .requestMatchers(
                        API_MATCHER.matcher(HttpMethod.OPTIONS, PREFIXED_BRANDING_PATH),
                        API_MATCHER.matcher(HttpMethod.OPTIONS, BRANDING_PATH))
                    .permitAll()
                    .requestMatchers(
                        API_MATCHER.matcher(HttpMethod.GET, PREFIXED_BRANDING_PATH),
                        API_MATCHER.matcher(HttpMethod.GET, BRANDING_PATH))
                    .permitAll()
                    // Writes, and anything else that reaches this path.
                    .anyRequest()
                    .authenticated())

        // Answers an unauthenticated write with the platform realm, so a branding 401 names
        // the chain that produced it.
        .exceptionHandling(eh -> eh.authenticationEntryPoint(platformAuthenticationEntryPoint));

    if (fineractProperties.getSecurity().getCors().isEnabled()) {
      http.cors(cors -> cors.configurationSource(brandingCorsConfigurationSource()));
    }

    return http.build();
  }

  /**
   * @return matcher claiming branding under both prefixes
   */
  private RequestMatcher brandingRequestMatcher() {
    return new OrRequestMatcher(
        API_MATCHER.matcher(PREFIXED_BRANDING_PATH), API_MATCHER.matcher(BRANDING_PATH));
  }

  /**
   * Authenticates against the platform user store, not the self-service one.
   *
   * @return tenant resolving basic authentication filter scoped to branding
   */
  private TenantAwareBasicAuthenticationFilter brandingAuthenticationFilter() {
    final TenantAwareBasicAuthenticationFilter filter =
        new TenantAwareBasicAuthenticationFilter(
            platformAuthenticationManager(),
            platformAuthenticationEntryPoint,
            toApiJsonSerializer,
            configurationDomainService,
            cacheWritePlatformService,
            userNotificationService,
            basicAuthTenantDetailsService,
            businessDateReadPlatformService);

    filter.setRequestMatcher(brandingRequestMatcher());
    return filter;
  }

  /**
   * @return manager backed solely by the platform authentication provider
   */
  private AuthenticationManager platformAuthenticationManager() {
    return new ProviderManager(platformAuthenticationProvider);
  }

  /**
   * Mirrors the CORS configuration of the other chains, so a browser client is not blocked on a
   * response this chain produced.
   *
   * @return CORS source for branding requests
   */
  private CorsConfigurationSource brandingCorsConfigurationSource() {
    final CorsConfiguration config = new CorsConfiguration();
    final FineractProperties.CorsProperties corsConfiguration =
        fineractProperties.getSecurity().getCors();
    config.setAllowedOriginPatterns(corsConfiguration.getAllowedOriginPatterns());
    config.setAllowedMethods(corsConfiguration.getAllowedMethods());
    config.setAllowedHeaders(corsConfiguration.getAllowedHeaders());
    config.setExposedHeaders(corsConfiguration.getExposedHeaders());
    config.setAllowCredentials(corsConfiguration.isAllowCredentials());

    final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
