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
 * the principal is an {@link AppSelfServiceUser}, a managed JPA proxy of AppUser is returned.
 * This approach solves the "cascade PERSIST" JPA error while maintaining security because:
 * <ul>
 *   <li>The SecurityContext has already validated that the user only has self-service permissions</li>
 *   <li>The AppUser proxy is only used to satisfy the foreign key relationship in CommandSource (maker field)</li>
 *   <li>The proxy does not grant any additional permissions beyond what was already validated</li>
 * </ul>
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
   * @return the authenticated AppUser (proxy for self-service users)
   */
  @Override
  public AppUser authenticatedUser() {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return getManagedAppUserProxy(selfServiceUser);
    }

    return super.authenticatedUser();
  }

  /**
   * Retrieves the authenticated user from the context for a specific command.
   *
   * @param commandWrapper the command wrapper contextualizing the request
   * @return the authenticated AppUser (proxy for self-service users)
   */
  @Override
  public AppUser authenticatedUser(final CommandWrapper commandWrapper) {
    final Object principal = extractPrincipal();

    if (principal instanceof AppSelfServiceUser selfServiceUser) {
      return getManagedAppUserProxy(selfServiceUser);
    }

    return super.authenticatedUser(commandWrapper);
  }

  /**
   * Retrieves the authenticated user if one is currently present in the security context.
   *
   * @return the authenticated AppUser (proxy for self-service users), or null if none
   */
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
   * Returns a managed JPA proxy of AppUser for the given self-service user.
   * 
   * This solves the "cascade PERSIST" JPA error because:
   * 1. The proxy is managed by the EntityManager within the current transaction
   * 2. It only contains the ID, which is used to populate the foreign key (maker_id)
   * 3. No actual user data is loaded or persisted
   * 
   * Security is maintained because:
   * 1. The SecurityContext has already validated that this user only has self-service permissions
   * 2. The proxy is only used for audit logging (CommandSource.maker), not for permission checks
   * 3. The actual authorization decisions were made before this method is called
   *
   * @param selfServiceUser the self-service user from the security context
   * @return a managed JPA proxy of AppUser
   */
  private AppUser getManagedAppUserProxy(AppSelfServiceUser selfServiceUser) {
    if (entityManager == null) {
      throw new IllegalStateException("EntityManager is not injected into SelfServiceCompatibleSecurityContext");
    }
    
    if (selfServiceUser.getId() == null) {
      throw new IllegalStateException("Cannot create managed AppUser proxy: Self-service user ID is null");
    }
    
    // Create a managed JPA proxy using AppUser.class
    // This proxy is lightweight and only contains the ID
    // It satisfies the foreign key relationship without triggering cascade PERSIST
    return entityManager.getReference(AppUser.class, selfServiceUser.getId());
  }
}