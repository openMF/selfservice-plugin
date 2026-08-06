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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.selfservice.client.service.SelfServiceClientReadPlatformService;
import org.apache.fineract.selfservice.kyc.service.KycFeatureStatusReadService;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.security.exception.SelfServicePasswordResetRequiredException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService;
import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService.TokenPair;
import org.apache.fineract.selfservice.security.service.SelfServiceDeviceFingerprintService;
import org.apache.fineract.selfservice.security.service.SelfServiceOfficeAddressReadService;
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
  @Mock private KycFeatureStatusReadService kycFeatureStatusReadService;
  @Mock private SelfServiceOfficeAddressReadService officeAddressReadPlatformService;
  @Mock private SelfServiceAuthenticationTokenService selfServiceAuthenticationTokenService;
  @Mock private NotificationDeliveryModeUtil notificationDeliveryModeUtil;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private SelfServiceDeviceFingerprintService deviceFingerprintService;
  @Mock private SelfServiceOnboardingStepService onboardingStepService;

  private SelfAuthenticationApiResource resource;

  @BeforeEach
  void setUp() {
    TokenPair mockTokens = new TokenPair("mock-access-token-123", "mock-refresh-token-456");
    lenient()
        .when(selfServiceAuthenticationTokenService.generateTokens(any(), any()))
        .thenReturn(mockTokens);

    lenient()
        .when(transactionDateUtil.getCurrentTenantLocalDateTime())
        .thenReturn(LocalDateTime.of(2026, 1, 2, 10, 0, 0));
    lenient().when(deviceFingerprintService.isKnownDevice(any(), any())).thenReturn(true);
    lenient()
        .when(deviceFingerprintService.registerOrTouch(any(), any(), any(Boolean.class)))
        .thenReturn(null);

    resource =
        new SelfAuthenticationApiResource(
            daoAuthenticationProvider,
            toApiJsonSerializer,
            securityContext,
            clientReadPlatformService,
            applicationEventPublisher,
            environment,
            appUserRepository,
            kycFeatureStatusReadService,
            officeAddressReadPlatformService,
            selfServiceAuthenticationTokenService,
            notificationDeliveryModeUtil,
            transactionDateUtil,
            deviceFingerprintService,
            onboardingStepService);
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
    when(clientReadPlatformService.retrieveSelfServiceUserClients(any()))
        .thenReturn(Collections.emptyList());
    when(officeAddressReadPlatformService.retrieveOfficeCountryByClientId(any())).thenReturn("US");
    when(kycFeatureStatusReadService.getKycFeatureStatus(any())).thenReturn(null);

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