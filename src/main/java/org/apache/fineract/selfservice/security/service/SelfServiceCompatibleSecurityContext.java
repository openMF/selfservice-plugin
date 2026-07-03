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
import jakarta.persistence.NoResultException;
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

  @Override
  public AppUser authenticatedUser() {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return getManagedAppUserProxy(selfServiceUser);
    }

    return super.authenticatedUser();
  }

  @Override
  public AppUser authenticatedUser(final CommandWrapper commandWrapper) {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return getManagedAppUserProxy(selfServiceUser);
    }

    return super.authenticatedUser(commandWrapper);
  }

  @Override
  public AppUser getAuthenticatedUserIfPresent() {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return getManagedAppUserProxy(selfServiceUser);
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
   * CRITICAL FIX: Returns a managed JPA proxy for the AppUser.
   * If the AppSelfServiceUser object in the SecurityContext has a null ID 
   * (which can happen if the UserDetails object was constructed without 
   * copying the ID from the database entity), we fall back to looking up 
   * the ID by username to ensure we always pass a valid ID to getReference().
   */
  private AppUser getManagedAppUserProxy(AppSelfServiceUser selfServiceUser) {
    if (entityManager == null) {
      throw new IllegalStateException("EntityManager is not injected into SelfServiceCompatibleSecurityContext");
    }

    Long userId = selfServiceUser.getId();

    // Fallback: If the ID is null, look it up by username to get a valid ID for the JPA proxy.
    if (userId == null && selfServiceUser.getUsername() != null) {
      try {
        userId = (Long) entityManager.createQuery(
                "SELECT u.id FROM AppUser u WHERE u.username = :username")
            .setParameter("username", selfServiceUser.getUsername())
            .getSingleResult();
      } catch (NoResultException e) {
        throw new IllegalStateException("Could not find AppUser with username: " + selfServiceUser.getUsername(), e);
      }
    }

    if (userId == null) {
      throw new IllegalStateException("Cannot create managed AppUser proxy: User ID is null and username is not available.");
    }

    // getReference returns a lazy JPA proxy that is ALWAYS considered "managed" 
    // by the persistence context. This perfectly satisfies the foreign key 
    // relationship without triggering a cascade PERSIST error.
    return entityManager.getReference(AppUser.class, userId);
  }
}