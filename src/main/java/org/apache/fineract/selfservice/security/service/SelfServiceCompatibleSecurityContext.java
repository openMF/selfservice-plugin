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
package org.apache.fineract.selfservice.security.service;

import java.lang.reflect.Field;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

/**
 * Extends the core {@link SpringSecurityPlatformSecurityContext} to handle both {@link AppUser} and
 * {@link AppSelfServiceUser} principals.
 *
 * <p>Overrides {@code authenticatedUser()} and {@code getAuthenticatedUserIfPresent()} so that when
 * the principal is an {@link AppSelfServiceUser}, a minimal {@link AppUser} stub is returned,
 * allowing core read services to pass their guard checks.
 */
public class SelfServiceCompatibleSecurityContext extends SpringSecurityPlatformSecurityContext {

  public SelfServiceCompatibleSecurityContext(
      ConfigurationDomainService configurationDomainService) {
    super(configurationDomainService);
  }

  /**
   * Retrieves the authenticated user, wrapping self-service users in a stub.
   *
   * @return the authenticated AppUser
   */
  @Override
  public AppUser authenticatedUser() {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return toAppUserStub(selfServiceUser);
    }

    return super.authenticatedUser();
  }

  /**
   * Retrieves the authenticated user from the context for a specific command.
   *
   * @param commandWrapper the command wrapper contextualizing the request
   * @return the authenticated AppUser
   */
  @Override
  public AppUser authenticatedUser(final CommandWrapper commandWrapper) {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return toAppUserStub(selfServiceUser);
    }

    return super.authenticatedUser(commandWrapper);
  }

  /**
   * Retrieves the authenticated user if one is currently present in the security context.
   *
   * @return the authenticated AppUser, or null if none
   */
  @Override
  public AppUser getAuthenticatedUserIfPresent() {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return toAppUserStub(selfServiceUser);
    }

    return super.getAuthenticatedUserIfPresent();
  }

  private Object extractPrincipal() {
    final SecurityContext context = SecurityContextHolder.getContext();
    if (context != null) {
      final Authentication auth = context.getAuthentication();
      if (auth != null) {
        return auth.getPrincipal();
      }
    }
    return null;
  }

  private AppUser toAppUserStub(AppSelfServiceUser selfServiceUser) {
    final User springUser =
        new User(
            selfServiceUser.getUsername(),
            selfServiceUser.getPassword(),
            selfServiceUser.isEnabled(),
            selfServiceUser.isAccountNonExpired(),
            selfServiceUser.isCredentialsNonExpired(),
            selfServiceUser.isAccountNonLocked(),
            selfServiceUser.getAuthorities());
    final AppUser stub =
        new AppUser(
            selfServiceUser.getOffice(),
            springUser,
            selfServiceUser.getRoles(),
            selfServiceUser.getEmail(),
            selfServiceUser.getFirstname(),
            selfServiceUser.getLastname(),
            null,
            true,
            false);
    setId(stub, selfServiceUser.getId());
    return stub;
  }

  private void setId(AppUser stub, Long id) {
    try {
      Field idField = AppUser.class.getSuperclass().getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(stub, id);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException("Failed to set id on AppUser stub", e);
    }
  }
}
