/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.selfservice.account.data.RemittanceConfirmRequest;
import org.apache.fineract.selfservice.account.data.RemittancePayoutRequest;
import org.apache.fineract.selfservice.account.data.RemittancePrepareRequest;
import org.apache.fineract.selfservice.account.data.RemittanceResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceRemittance;
import org.apache.fineract.selfservice.account.domain.SelfServiceRemittanceRepository;
import org.apache.fineract.selfservice.security.exception.SelfServiceAccessDeniedException;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
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
  void rejectPayout_sendsClientTimestampInUtc_whenServerIsNotOnUtc() {
    when(externalService.rejectPayout(anyString(), anyString(), any())).thenReturn("{}");
    TimeZone serverZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Costa_Rica"));
    Instant timestamp;
    try {
      remittanceService.rejectPayout("RIA", "PIN1", new RemittancePayoutRequest());

      ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
      verify(externalService).rejectPayout(eq("RIA"), eq("PIN1"), body.capture());
      // a value with no zone (server wall clock) can't be parsed as an Instant
      timestamp = Instant.parse((String) ((Map<?, ?>) body.getValue()).get("clientTimestampUtc"));
    } finally {
      TimeZone.setDefault(serverZone);
    }

    // Costa Rica is UTC-6, so the old wall-clock value was 6 hours off
    assertTrue(Duration.between(timestamp, Instant.now()).abs().toMinutes() < 1);
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

  @Test
  void confirmPayout_userWithoutLinkedClient_isRejected() {
    loginUserWithClientMappings(Set.of());

    GeneralPlatformDomainRuleException ex =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> remittanceService.confirmPayout("RIA", "PIN1", new RemittancePayoutRequest()));

    assertEquals("error.msg.remittance.no.client.mapping", ex.getGlobalisationMessageCode());
    verify(externalService, never()).confirmPayout(anyString(), anyString(), anyString(), any());
  }

  @Test
  void findRemittance_userWithoutLinkedClient_isRejected() {
    loginUserWithClientMappings(Set.of());
    when(externalService.findTransaction("RIA", "PIN1")).thenReturn("{\"status\":\"AVAILABLE\"}");

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> remittanceService.findRemittance("RIA", "PIN1"));

    verify(remittanceRepository, never()).save(any());
  }

  @Test
  void findRemittance_samePinFromAnotherVendor_stillSavesRowForThisVendor() {
    // another vendor already has a row for this PIN; PINs are only unique per vendor
    when(remittanceRepository.findByPinAndVendor("PIN1", "OTHER"))
        .thenReturn(Optional.of(new SelfServiceRemittance()));
    when(externalService.findTransaction("RIA", "PIN1")).thenReturn("{\"status\":\"AVAILABLE\"}");
    when(remittanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    remittanceService.findRemittance("RIA", "PIN1");

    verify(remittanceRepository).findByPinAndVendor("PIN1", "RIA");
    ArgumentCaptor<SelfServiceRemittance> saved =
        ArgumentCaptor.forClass(SelfServiceRemittance.class);
    verify(remittanceRepository).save(saved.capture());
    assertEquals("RIA", saved.getValue().getVendor());
    assertEquals("PIN1", saved.getValue().getPin());
    assertEquals(OWN_CLIENT_ID, saved.getValue().getClientId());
  }

  @Test
  void findRemittance_existingRowForThisVendor_isNotSavedAgain() {
    when(remittanceRepository.findByPinAndVendor("PIN1", "RIA"))
        .thenReturn(Optional.of(new SelfServiceRemittance()));
    when(externalService.findTransaction("RIA", "PIN1")).thenReturn("{\"status\":\"AVAILABLE\"}");

    remittanceService.findRemittance("RIA", "PIN1");

    verify(remittanceRepository, never()).save(any());
  }

  @Test
  void prepareRemittance_userWithoutLinkedClient_isRejected() {
    loginUserWithClientMappings(Set.of());

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> remittanceService.prepareRemittance(new RemittancePrepareRequest()));

    verify(remittanceRepository, never()).save(any());
  }

  @Test
  void confirmRemittance_userWithoutLinkedClient_isRejected() {
    loginUserWithClientMappings(null);

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> remittanceService.confirmRemittance(new RemittanceConfirmRequest()));

    verify(externalService, never()).createRemittance(any(), any());
    verify(remittanceRepository, never()).save(any());
  }

  private void loginUserWithClientMappings(Set<AppSelfServiceUserClientMapping> mappings) {
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getAppUserClientMappings()).thenReturn(mappings);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }
}
