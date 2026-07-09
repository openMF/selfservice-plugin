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
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private AppUserRepository appUserRepository;

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
   * Returns a managed JPA proxy of AppUser for the given self-service user.
   * 
   * This solves the "cascade PERSIST" JPA error because:
   * 1. The proxy is managed by the EntityManager within the current transaction
   * 2. It only contains the ID, which is used to populate the foreign key (maker_id)
   * 3. No actual user data is loaded or persisted
   * 
   * If the self-service user's ID is null (which can happen if the authentication filter
   * doesn't properly set it), we fall back to looking up the user by username.
   *
   * @param selfServiceUser the self-service user from the security context
   * @return a managed JPA proxy of AppUser
   */
  private AppUser getManagedAppUserProxy(AppSelfServiceUser selfServiceUser) {
    if (entityManager == null) {
      throw new IllegalStateException("EntityManager is not injected into SelfServiceCompatibleSecurityContext");
    }
    
    Long userId = selfServiceUser.getId();
    
    // FIX: Si el ID es null, buscar el usuario por username
    if (userId == null) {
      String username = selfServiceUser.getUsername();
      if (username == null || username.isBlank()) {
        throw new IllegalStateException("Cannot create managed AppUser proxy: Both ID and username are null");
      }
      
      AppUser user = appUserRepository.findAppUserByName(username);
      if (user == null) {
        throw new IllegalStateException("Cannot find AppUser with username: " + username);
      }
      
      userId = user.getId();
    }
    
    return entityManager.getReference(AppUser.class, userId);
  }
}