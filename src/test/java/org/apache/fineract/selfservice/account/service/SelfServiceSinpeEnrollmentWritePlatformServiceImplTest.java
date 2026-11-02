/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionRequest;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfServiceSinpeEnrollmentWritePlatformServiceImplTest {

  private static final Long OWN_CLIENT_ID = 7L;
  private static final String OWN_CEDULA = "1-1111-1111";
  private static final String OTHER_CEDULA = "2-2222-2222";
  private static final String PHONE = "88887777";
  private static final String OTP = "123456";

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private SelfServiceRegistrationRepository registrationRepository;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private SinpeExternalApiClient sinpeExternalApiClient;
  @Mock private TransactionDateUtil transactionDateUtil;

  @InjectMocks private SelfServiceSinpeEnrollmentWritePlatformServiceImpl service;

  private Client client;

  @BeforeEach
  void setUp() {
    client = mock(Client.class);
    when(client.getId()).thenReturn(OWN_CLIENT_ID);
    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getClient()).thenReturn(client);
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getAppUserClientMappings()).thenReturn(Set.of(mapping));
    when(context.authenticatedSelfServiceUser()).thenReturn(user);

    SelfServiceRegistration registration = mock(SelfServiceRegistration.class);
    when(registration.getMobileNumber()).thenReturn(PHONE);
    when(registrationRepository.findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
            OWN_CLIENT_ID, SelfServiceRequestType.SINPE_ENROLLMENT, OTP))
        .thenReturn(Optional.of(registration));
  }

  @Test
  void createSubscription_usesOwnCedula() {
    when(client.getExternalId()).thenReturn(new ExternalId(OWN_CEDULA));

    service.createSubscription(subscriptionRequest(null), OTP);

    assertEquals(OWN_CEDULA, sentRequest().getCustomerId());
  }

  @Test
  void createSubscription_ignoresCustomerIdFromRequest() {
    when(client.getExternalId()).thenReturn(new ExternalId(OWN_CEDULA));

    service.createSubscription(subscriptionRequest(OTHER_CEDULA), OTP);

    assertEquals(OWN_CEDULA, sentRequest().getCustomerId());
  }

  @Test
  void createSubscription_withoutExternalId_isRejected() {
    when(client.getExternalId()).thenReturn(ExternalId.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.createSubscription(subscriptionRequest(OTHER_CEDULA), OTP));

    verify(sinpeExternalApiClient, never()).createSubscription(any());
  }

  private SinpeSubscriptionRequest subscriptionRequest(String customerId) {
    SinpeSubscriptionRequest request = new SinpeSubscriptionRequest();
    request.setPhoneNumber(PHONE);
    request.setCustomerId(customerId);
    return request;
  }

  private SinpeSubscriptionRequest sentRequest() {
    ArgumentCaptor<SinpeSubscriptionRequest> captor =
        ArgumentCaptor.forClass(SinpeSubscriptionRequest.class);
    verify(sinpeExternalApiClient).createSubscription(captor.capture());
    return captor.getValue();
  }
}
