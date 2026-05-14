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
package org.apache.fineract.selfservice.security.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.CacheWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.data.PlatformRequestLog;
import org.apache.fineract.infrastructure.security.filter.TenantAwareBasicAuthenticationFilter;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.notification.service.UserNotificationService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Overrides the core filter to avoid a ClassCastException in onSuccessfulAuthentication, where the
 * parent casts the principal to AppUser. Self-service authentication returns AppSelfServiceUser
 * which is not an AppUser.
 */
public class SelfServiceBasicAuthenticationFilter extends TenantAwareBasicAuthenticationFilter {

  public SelfServiceBasicAuthenticationFilter(
      AuthenticationManager authenticationManager,
      AuthenticationEntryPoint authenticationEntryPoint,
      ToApiJsonSerializer<PlatformRequestLog> toApiJsonSerializer,
      ConfigurationDomainService configurationDomainService,
      CacheWritePlatformService cacheWritePlatformService,
      UserNotificationService userNotificationService,
      AuthTenantDetailsService basicAuthTenantDetailsService,
      BusinessDateReadPlatformService businessDateReadPlatformService) {
    super(
        authenticationManager,
        authenticationEntryPoint,
        toApiJsonSerializer,
        configurationDomainService,
        cacheWritePlatformService,
        userNotificationService,
        basicAuthTenantDetailsService,
        businessDateReadPlatformService);
  }

  @Override
  protected void onSuccessfulAuthentication(
      HttpServletRequest request, HttpServletResponse response, Authentication authResult)
      throws IOException {
    // No-op: skip parent's AppUser cast. Self-service users don't need
    // the X-Notification-Refresh header that the core filter sets.
  }
}
