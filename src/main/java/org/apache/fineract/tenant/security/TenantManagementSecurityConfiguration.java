/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.security;

import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

/**
 * The master security context for tenant administration.
 *
 * <p>Tenant management administers the tenants themselves, so it cannot belong to any one of them.
 * Fineract's own chain resolves every user inside the tenant named by {@code
 * Fineract-Platform-TenantId}; a permission granted there - even {@code ALL_FUNCTIONS} - is a
 * tenant's permission. This chain instead authenticates {@code /v1/admin/tenants} against master
 * users stored in the tenant store, and requires the {@code SUPER_MASTER} role. No tenant header is
 * needed or consulted, and no tenant user can pass it.
 *
 * <p><strong>Why {@code /v1/admin/tenants}.</strong> Core Fineract already serves {@code
 * /v1/tenants/{tenantId}/oidc-config}. A chain claiming {@code /v1/tenants/**} would capture that
 * core endpoint and demand master credentials for it, so tenant administration lives under a
 * namespace core does not use.
 *
 * <p>Ordered ahead of Fineract's catch-all {@code /api/**} chain, the same way {@code
 * SelfServiceSecurityConfiguration} claims {@code /v1/self/**}, so it needs no change to core.
 *
 * <p>The authentication manager and password encoder are built here rather than registered as
 * beans: Fineract already defines beans of both types, and a second one could be injected where the
 * platform expects its own.
 */
@Configuration
public class TenantManagementSecurityConfiguration {

  static final String[] TENANT_ADMINISTRATION_PATHS = {
    "/api/v1/admin/tenants", "/api/v1/admin/tenants/**", "/v1/admin/tenants", "/v1/admin/tenants/**"
  };

  /**
   * The master chain, ordered on the bean itself.
   *
   * <p>Spring Security sorts {@code SecurityFilterChain} beans by each bean's own order; an
   * {@code @Order} on the enclosing {@code @Configuration} class does not carry over to the beans
   * it declares. Left unordered, this chain and Fineract's catch-all {@code /api/**} chain would be
   * tried in whatever order the beans happened to register, and requests could reach tenant
   * authentication instead of master authentication.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain tenantManagementSecurityFilterChain(
      final HttpSecurity http,
      final TenantMasterUserStore masterUserStore,
      final FineractProperties fineractProperties)
      throws Exception {

    final DaoAuthenticationProvider provider =
        new DaoAuthenticationProvider(PasswordEncoderFactories.createDelegatingPasswordEncoder());
    provider.setUserDetailsService(username -> toUserDetails(masterUserStore, username));

    final BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
    entryPoint.setRealmName("Fineract Tenant Management");

    http.securityMatcher(TENANT_ADMINISTRATION_PATHS)
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authenticationManager(new ProviderManager(provider))
        .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
        .authorizeHttpRequests(
            auth ->
                auth
                    // CORS preflight carries no credentials by design.
                    .requestMatchers(HttpMethod.OPTIONS, TENANT_ADMINISTRATION_PATHS)
                    .permitAll()
                    .anyRequest()
                    .hasRole(TenantMasterAccess.SUPER_MASTER_ROLE));

    // The administration UI runs in a browser on another origin, as the self-service
    // clients do, so this chain honours the same CORS configuration.
    if (fineractProperties.getSecurity().getCors().isEnabled()) {
      http.cors(Customizer.withDefaults());
    }

    return http.build();
  }

  /**
   * Adapts a stored master user for Spring Security.
   *
   * <p>An unknown name and a wrong password both surface as the same 401 through the entry point,
   * so a caller cannot use this endpoint to discover which master usernames exist.
   */
  private static UserDetails toUserDetails(
      final TenantMasterUserStore store, final String username) {
    final TenantMasterUserStore.MasterUser user =
        store
            .findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown master user"));
    return User.withUsername(user.username())
        .password(user.passwordHash())
        .roles(user.role())
        .disabled(!user.enabled())
        .build();
  }
}
