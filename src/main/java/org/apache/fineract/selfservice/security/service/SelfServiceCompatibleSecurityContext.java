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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Extends the core {@link SpringSecurityPlatformSecurityContext} to handle both {@link AppUser} and
 * {@link AppSelfServiceUser} principals.
 *
 * <p>Overrides {@code authenticatedUser()} and {@code getAuthenticatedUserIfPresent()} so that when
 * the principal is an {@link AppSelfServiceUser}, a managed JPA proxy is returned.
 * This prevents "new object found through a relationship" JPA errors when core services
 * (like CommandSourceService) try to save entities with the user as a foreign key reference.
 */
public class SelfServiceCompatibleSecurityContext extends SpringSecurityPlatformSecurityContext {

  // Inject the EntityManager to create managed JPA proxies
  @PersistenceContext
  private EntityManager entityManager;

  public SelfServiceCompatibleSecurityContext(
      ConfigurationDomainService configurationDomainService) {
    super(configurationDomainService);
  }

  /**
   * Retrieves the authenticated user, returning a managed JPA proxy for self-service users.
   *
   * @return the authenticated AppUser
   */
  @Override
  public AppUser authenticatedUser() {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return getManagedAppUserProxy(selfServiceUser.getId());
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
      return getManagedAppUserProxy(selfServiceUser.getId());
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
      return getManagedAppUserProxy(selfServiceUser.getId());
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

  /**
   * CRITICAL FIX: Instead of creating a new, detached AppUser stub (which causes
   * "new object found through a relationship" JPA errors when services try to save 
   * entities with this user as a foreign key), we return a managed JPA proxy.
   * 
   * EntityManager.getReference() returns a lazy proxy that is ALWAYS considered 
   * "managed" by the persistence context. This perfectly satisfies the foreign key 
   * relationship without triggering a cascade PERSIST error and without hitting 
   * the database to load the full entity graph.
   */
  private AppUser getManagedAppUserProxy(Long userId) {
    if (entityManager == null) {
      throw new IllegalStateException("EntityManager is not injected into SelfServiceCompatibleSecurityContext");
    }
    return entityManager.getReference(AppUser.class, userId);
  }
}