/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Set;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.selfservice.client.service.SelfServiceClientReadPlatformService;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.security.exception.SelfServicePasswordResetRequiredException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.data.AppSelfServiceUserData;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfAuthenticationApiResourceTest {

  @Mock private DaoAuthenticationProvider daoAuthenticationProvider;
  @Mock private ToApiJsonSerializer<AppSelfServiceUserData> toApiJsonSerializer;
  @Mock private PlatformSelfServiceSecurityContext securityContext;
  @Mock private SelfServiceClientReadPlatformService clientReadPlatformService;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private Environment environment;
  @Mock private HttpServletRequest httpServletRequest;
  @Mock private AppSelfServiceUserRepository appUserRepository;

  private SelfAuthenticationApiResource resource;

  @BeforeEach
  void setUp() {
    resource =
        new SelfAuthenticationApiResource(
            daoAuthenticationProvider,
            toApiJsonSerializer,
            securityContext,
            clientReadPlatformService,
            applicationEventPublisher,
            environment,
            appUserRepository);
  }

  @Test
  void authenticate_nullBody_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> resource.authenticate(null, true, httpServletRequest));
    assertThrows(
        IllegalArgumentException.class,
        () -> resource.authenticate("null", true, httpServletRequest));
  }

  @Test
  void authenticate_throwsOnNullUsernameOrPassword() {
    assertThrows(
        IllegalArgumentException.class, () -> resource.authenticate("", true, httpServletRequest));
    assertThrows(
        IllegalArgumentException.class,
        () -> resource.authenticate("{\"username\":\"\"}", true, httpServletRequest));
    assertThrows(
        IllegalArgumentException.class,
        () -> resource.authenticate("{\"password\":\"\"}", true, httpServletRequest));
  }

  @Test
  void authenticate_returnsUserDataOnSuccess() {
    String requestBody = "{\"username\":\"admin\", \"password\":\"pass\"}";
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    AppSelfServiceUser principal = mock(AppSelfServiceUser.class);
    Office office = mock(Office.class);
    when(principal.getOffice()).thenReturn(office);
    when(office.getId()).thenReturn(1L);
    when(office.getName()).thenReturn("Head Office");
    when(principal.getId()).thenReturn(100L);
    when(principal.getRoles()).thenReturn(Set.of(mock(Role.class)));
    when(auth.getPrincipal()).thenReturn(principal);
    when(auth.getAuthorities()).thenReturn(Collections.emptyList());

    when(daoAuthenticationProvider.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenReturn(auth);
    when(securityContext.doesPasswordHasToBeRenewed(principal)).thenReturn(false);
    when(toApiJsonSerializer.serialize(any())).thenReturn("{}");

    String result = resource.authenticate(requestBody, true, httpServletRequest);

    assertNotNull(result);
    org.mockito.Mockito.verify(applicationEventPublisher)
        .publishEvent(any(SelfServiceNotificationEvent.class));
  }

  @Test
  void authenticate_throwsPasswordResetExceptionWhenResetRequired() {
    String requestBody = "{\"username\":\"admin\", \"password\":\"pass\"}";
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    AppSelfServiceUser principal = mock(AppSelfServiceUser.class);
    Office office = mock(Office.class);
    when(principal.getOffice()).thenReturn(office);
    when(principal.getId()).thenReturn(100L);
    when(principal.getRoles()).thenReturn(Set.of(mock(Role.class)));
    when(auth.getPrincipal()).thenReturn(principal);
    when(auth.getAuthorities()).thenReturn(Collections.emptyList());

    when(daoAuthenticationProvider.authenticate(any(UsernamePasswordAuthenticationToken.class)))
        .thenReturn(auth);
    when(securityContext.doesPasswordHasToBeRenewed(principal)).thenReturn(true);

    assertThrows(
        SelfServicePasswordResetRequiredException.class,
        () -> resource.authenticate(requestBody, true, httpServletRequest));
    verifyNoInteractions(applicationEventPublisher);
  }
}
