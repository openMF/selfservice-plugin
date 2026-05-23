/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.security.starter;

import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.CacheWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.filters.CallerIpTrackingFilter;
import org.apache.fineract.infrastructure.core.filters.CorrelationHeaderFilter;
import org.apache.fineract.infrastructure.core.filters.IdempotencyStoreFilter;
import org.apache.fineract.infrastructure.core.filters.IdempotencyStoreHelper;
import org.apache.fineract.infrastructure.core.filters.RequestResponseFilter;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.MDCWrapper;
import org.apache.fineract.infrastructure.instancemode.filter.FineractInstanceModeApiFilter;
import org.apache.fineract.infrastructure.jobs.filter.LoanCOBApiFilter;
import org.apache.fineract.infrastructure.jobs.filter.LoanCOBFilterHelper;
import org.apache.fineract.infrastructure.security.data.PlatformRequestLog;
import org.apache.fineract.infrastructure.security.filter.TwoFactorAuthenticationFilter;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.infrastructure.security.service.PlatformUserDetailsChecker;
import org.apache.fineract.infrastructure.security.service.TwoFactorService;
import org.apache.fineract.notification.service.UserNotificationService;
import org.apache.fineract.selfservice.security.filter.SelfServiceBasicAuthenticationFilter;
import org.apache.fineract.selfservice.security.service.SelfServiceUserAuthorizationManager;
import org.apache.fineract.selfservice.security.service.TenantAwareJpaPlatformSelfServiceUserDetailsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@Order(1) // Very important: Must have higher priority than main security config
public class SelfServiceSecurityConfiguration {

  private static final PathPatternRequestMatcher.Builder API_MATCHER =
      PathPatternRequestMatcher.withDefaults();

  @Autowired private ApplicationContext applicationContext;
  @Autowired private TenantAwareJpaPlatformSelfServiceUserDetailsService userDetailsService;
  @Autowired private FineractProperties fineractProperties;
  @Autowired private ToApiJsonSerializer<PlatformRequestLog> toApiJsonSerializer;
  @Autowired private ConfigurationDomainService configurationDomainService;
  @Autowired private CacheWritePlatformService cacheWritePlatformService;
  @Autowired private UserNotificationService userNotificationService;
  @Autowired private AuthTenantDetailsService basicAuthTenantDetailsService;
  @Autowired private BusinessDateReadPlatformService businessDateReadPlatformService;
  @Autowired private MDCWrapper mdcWrapper;
  @Autowired private FineractRequestContextHolder fineractRequestContextHolder;

  @Autowired(required = false)
  private LoanCOBFilterHelper loanCOBFilterHelper;

  @Autowired private IdempotencyStoreHelper idempotencyStoreHelper;
  @Autowired private PlatformUserDetailsChecker platformUserDetailsChecker;

  @Bean
  public SecurityFilterChain selfServiceSecurityFilterChain(HttpSecurity http) throws Exception {

    http
        // Apply only to self-service endpoints
        .securityMatcher("/api/v1/self/**", "/v1/self/**")

        // Disable CSRF for public self-service APIs
        .csrf(AbstractHttpConfigurer::disable)

        // Stateless session
        .sessionManagement(smc -> smc.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(tenantAwareBasicAuthenticationFilter(), SecurityContextHolderFilter.class)
        .addFilterAfter(requestResponseFilter(), ExceptionTranslationFilter.class)
        .addFilterAfter(correlationHeaderFilter(), RequestResponseFilter.class)
        .addFilterAfter(fineractInstanceModeApiFilter(), CorrelationHeaderFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // === PUBLIC ENDPOINTS ===
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/registration")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/registration/user")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/registration/client-user")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/self/registration/client-user/confirm")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/registration")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/registration/user")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/registration/client-user")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/registration/client-user/confirm")
                    .permitAll()

                    // Client Identity documents available in the platform
                    .requestMatchers(HttpMethod.GET, "/api/v1/self/registration/identifiers")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/self/registration/identifiers")
                    .permitAll()

                    // External System Client Identity
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/identity/retrieve")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/identity/retrieve")
                    .permitAll()

                    // Self authentication (login)
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/authentication")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/authentication")
                    .permitAll()

                    // Password Reset
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/password")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/password")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/password/request")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/password/request")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/password/renew")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/password/renew")
                    .permitAll()

                    // Public loan simulation endpoints (MX-250)
                    .requestMatchers(HttpMethod.GET, "/api/v1/self/loans/simulate/products")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/self/loans/simulate/products")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/self/loans/simulate/template")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/self/loans/simulate/template")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/self/loans/simulate")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/self/loans/simulate")
                    .permitAll()                    
                    // All other self-service endpoints require self-service authentication and must
                    // pass the self-service authorization manager (guards self vs non-self
                    // traffic).
                    .requestMatchers("/api/v1/self/**", "/v1/self/**")
                    .access(SelfServiceUserAuthorizationManager.selfServiceUserAuthManager())
                    .anyRequest()
                    .permitAll());

    // Optional: CORS if needed for mobile/web clients
    if (fineractProperties.getSecurity().getCors().isEnabled()) {
      http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
    }

    return http.build();
  }

  public RequestResponseFilter requestResponseFilter() {
    return new RequestResponseFilter();
  }

  public LoanCOBApiFilter loanCOBApiFilter() {
    return new LoanCOBApiFilter(loanCOBFilterHelper);
  }

  public TwoFactorAuthenticationFilter twoFactorAuthenticationFilter() {
    TwoFactorService twoFactorService = applicationContext.getBean(TwoFactorService.class);
    return new TwoFactorAuthenticationFilter(twoFactorService);
  }

  public FineractInstanceModeApiFilter fineractInstanceModeApiFilter() {
    return new FineractInstanceModeApiFilter(fineractProperties);
  }

  public IdempotencyStoreFilter idempotencyStoreFilter() {
    return new IdempotencyStoreFilter(
        fineractRequestContextHolder, idempotencyStoreHelper, fineractProperties);
  }

  public CorrelationHeaderFilter correlationHeaderFilter() {
    return new CorrelationHeaderFilter(fineractProperties, mdcWrapper);
  }

  public CallerIpTrackingFilter callerIpTrackingFilter() {
    return new CallerIpTrackingFilter(fineractProperties);
  }

  public SelfServiceBasicAuthenticationFilter tenantAwareBasicAuthenticationFilter()
      throws Exception {
    SelfServiceBasicAuthenticationFilter filter =
        new SelfServiceBasicAuthenticationFilter(
            selfServiceAuthenticationManager(),
            selfServiceBasicAuthenticationEntryPoint(),
            toApiJsonSerializer,
            configurationDomainService,
            cacheWritePlatformService,
            userNotificationService,
            basicAuthTenantDetailsService,
            businessDateReadPlatformService);

    // Must match both /api/v1/self/** and /v1/self/** endpoints.
    // Some self-service resources (e.g. runreports) are registered under /v1/self/** without
    // the /api prefix, so authentication filter must cover both patterns.
    filter.setRequestMatcher(
        new org.springframework.security.web.util.matcher.OrRequestMatcher(
            API_MATCHER.matcher("/api/v1/self/**"), API_MATCHER.matcher("/v1/self/**")));
    return filter;
  }

  @Bean(name = "selfServiceBasicAuthenticationEntryPoint")
  public BasicAuthenticationEntryPoint selfServiceBasicAuthenticationEntryPoint() {
    BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
    entryPoint.setRealmName("Fineract Self Service API");
    return entryPoint;
  }

  @Bean(name = "selfServiceAuthenticationProvider")
  public DaoAuthenticationProvider selfServiceAuthProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
    authProvider.setUserDetailsService(userDetailsService);
    authProvider.setPasswordEncoder(selfServicePasswordEncoder());
    authProvider.setPreAuthenticationChecks(
        new org.apache.fineract.selfservice.security.service.SelfServiceUserDetailsChecker(
            platformUserDetailsChecker));
    authProvider.setPostAuthenticationChecks(platformUserDetailsChecker);
    return authProvider;
  }

  public PasswordEncoder selfServicePasswordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  public AuthenticationManager selfServiceAuthenticationManager() throws Exception {
    ProviderManager providerManager = new ProviderManager(selfServiceAuthProvider());
    providerManager.setEraseCredentialsAfterAuthentication(false);
    return providerManager;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    FineractProperties.CorsProperties corsConfiguration =
        fineractProperties.getSecurity().getCors();
    config.setAllowedOriginPatterns(corsConfiguration.getAllowedOriginPatterns());
    config.setAllowedMethods(corsConfiguration.getAllowedMethods());
    config.setAllowedHeaders(corsConfiguration.getAllowedHeaders());
    config.setExposedHeaders(corsConfiguration.getExposedHeaders());
    config.setAllowCredentials(corsConfiguration.isAllowCredentials());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
