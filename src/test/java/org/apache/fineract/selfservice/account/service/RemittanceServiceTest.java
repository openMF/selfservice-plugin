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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.selfservice.account.data.RemittancePayoutRequest;
import org.apache.fineract.selfservice.account.data.RemittanceResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceRemittanceRepository;
import org.apache.fineract.selfservice.security.exception.SelfServiceAccessDeniedException;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemittanceServiceTest {

  private static final Long OWN_CLIENT_ID = 7L;

  @Mock private RemittanceExternalService externalService;
  @Mock private SelfServiceRemittanceRepository remittanceRepository;
  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private SelfServiceOwnershipGuard ownershipGuard;

  @InjectMocks private RemittanceService remittanceService;

  @BeforeEach
  void setUp() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(OWN_CLIENT_ID);
    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getClient()).thenReturn(client);
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getAppUserClientMappings()).thenReturn(Set.of(mapping));
    when(context.authenticatedSelfServiceUser()).thenReturn(user);

    when(externalService.confirmPayout(anyString(), anyString(), anyString(), any()))
        .thenReturn("{}");
    when(remittanceRepository.findByPinAndVendor(anyString(), anyString()))
        .thenReturn(Optional.empty());
  }

  @Test
  void confirmPayout_withoutClientId_usesOwnClient() {
    RemittanceResponse response =
        remittanceService.confirmPayout("RIA", "PIN1", new RemittancePayoutRequest());

    assertEquals("PAID", response.getStatus());
    verify(externalService).confirmPayout(eq("RIA"), eq("PIN1"), eq("7"), any());
    verifyNoInteractions(ownershipGuard);
  }

  @Test
  void confirmPayout_withOwnClientId_isAccepted() {
    RemittancePayoutRequest request = new RemittancePayoutRequest();
    request.setClientIdMifos("7");

    remittanceService.confirmPayout("RIA", "PIN1", request);

    verify(ownershipGuard).validateClientOwnership(OWN_CLIENT_ID);
    verify(externalService).confirmPayout(eq("RIA"), eq("PIN1"), eq("7"), any());
  }

  @Test
  void confirmPayout_withAnotherClientsId_isRejected() {
    doThrow(new ClientNotFoundException(99L)).when(ownershipGuard).validateClientOwnership(99L);
    RemittancePayoutRequest request = new RemittancePayoutRequest();
    request.setClientIdMifos("99");

    assertThrows(
        ClientNotFoundException.class,
        () -> remittanceService.confirmPayout("RIA", "PIN1", request));

    verify(externalService, never()).confirmPayout(anyString(), anyString(), anyString(), any());
  }

  @Test
  void confirmPayout_withNonNumericClientId_isRejected() {
    doThrow(mock(SelfServiceAccessDeniedException.class))
        .when(ownershipGuard)
        .validateClientOwnership(isNull());
    RemittancePayoutRequest request = new RemittancePayoutRequest();
    request.setClientIdMifos("7/../../admin");

    assertThrows(
        SelfServiceAccessDeniedException.class,
        () -> remittanceService.confirmPayout("RIA", "PIN1", request));

    verify(externalService, never()).confirmPayout(anyString(), anyString(), anyString(), any());
  }
}
