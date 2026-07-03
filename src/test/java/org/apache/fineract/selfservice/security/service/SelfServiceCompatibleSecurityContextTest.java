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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class SelfServiceCompatibleSecurityContextTest {

    private SelfServiceCompatibleSecurityContext context;
    private ConfigurationDomainService configurationDomainService;
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        configurationDomainService = mock(ConfigurationDomainService.class);
        entityManager = mock(EntityManager.class);
        context = new SelfServiceCompatibleSecurityContext(configurationDomainService);
        
        // FIX: Inject the mocked EntityManager into the context using reflection.
        // This is required because @PersistenceContext field injection only works 
        // when the bean is managed by Spring, not when instantiated manually with 'new'.
        ReflectionTestUtils.setField(context, "entityManager", entityManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedUser_selfServicePrincipal_returnsStub() {
        AppSelfServiceUser selfServiceUser = createMockSelfServiceUser(1L, "testuser");
        setAuthentication(selfServiceUser);
        
        AppUser expectedProxy = mock(AppUser.class);
        when(entityManager.getReference(AppUser.class, 1L)).thenReturn(expectedProxy);

        AppUser result = context.authenticatedUser();

        assertNotNull(result);
        assertEquals(expectedProxy, result);
    }

    @Test
    void authenticatedUserWithCommandWrapper_selfServicePrincipal_returnsStubWithCorrectFields() {
        AppSelfServiceUser selfServiceUser = createMockSelfServiceUser(2L, "cmduser");
        setAuthentication(selfServiceUser);
        
        AppUser expectedProxy = mock(AppUser.class);
        when(entityManager.getReference(AppUser.class, 2L)).thenReturn(expectedProxy);

        CommandWrapper commandWrapper = mock(CommandWrapper.class);
        AppUser result = context.authenticatedUser(commandWrapper);

        assertNotNull(result);
        assertEquals(expectedProxy, result);
    }

    @Test
    void authenticatedUserWithCommandWrapper_passwordExpiredSelfServiceUser_returnsStubWithoutThrowingResetPasswordException() {
        AppSelfServiceUser selfServiceUser = createMockSelfServiceUser(3L, "expireduser");
        setAuthentication(selfServiceUser);
        
        AppUser expectedProxy = mock(AppUser.class);
        when(entityManager.getReference(AppUser.class, 3L)).thenReturn(expectedProxy);

        CommandWrapper commandWrapper = mock(CommandWrapper.class);
        
        // Should not throw PasswordCannotBePreviouslyUsedException or similar
        AppUser result = context.authenticatedUser(commandWrapper);
        assertNotNull(result);
    }

    @Test
    void getAuthenticatedUserIfPresent_selfServicePrincipal_returnsStub() {
        AppSelfServiceUser selfServiceUser = createMockSelfServiceUser(4L, "presentuser");
        setAuthentication(selfServiceUser);
        
        AppUser expectedProxy = mock(AppUser.class);
        when(entityManager.getReference(AppUser.class, 4L)).thenReturn(expectedProxy);

        AppUser result = context.getAuthenticatedUserIfPresent();

        assertNotNull(result);
        assertEquals(expectedProxy, result);
    }

    private AppSelfServiceUser createMockSelfServiceUser(Long id, String username) {
        AppSelfServiceUser user = mock(AppSelfServiceUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(username);
        return user;
    }

    private void setAuthentication(AppSelfServiceUser principal) {
        UsernamePasswordAuthenticationToken auth = 
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}