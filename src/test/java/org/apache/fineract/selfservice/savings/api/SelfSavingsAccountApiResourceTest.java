/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.savings.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.UriInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.api.SavingsAccountChargesApiResource;
import org.apache.fineract.portfolio.savings.api.SavingsAccountTransactionsApiResource;
import org.apache.fineract.portfolio.savings.api.SavingsAccountsApiResource;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.selfservice.client.service.AppSelfServiceUserClientMapperReadService;
import org.apache.fineract.selfservice.savings.data.SelfSavingsAccountConstants;
import org.apache.fineract.selfservice.savings.data.SelfSavingsDataValidator;
import org.apache.fineract.selfservice.savings.service.AppuserSavingsMapperReadService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfSavingsAccountApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private SavingsAccountsApiResource savingsAccountsApiResource;
  @Mock private SavingsAccountChargesApiResource savingsAccountChargesApiResource;
  @Mock private SavingsAccountTransactionsApiResource savingsAccountTransactionsApiResource;
  @Mock private AppuserSavingsMapperReadService appuserSavingsMapperReadService;
  @Mock private SelfSavingsDataValidator dataValidator;
  @Mock private AppSelfServiceUserClientMapperReadService appUserClientMapperReadService;
  @Mock private UriInfo uriInfo;

  private SelfSavingsAccountApiResource resource;

  private static final Long USER_ID = 10L;
  private static final Long ACCOUNT_ID = 5L;
  private static final Long CLIENT_ID = 7L;

  private static SavingsAccountData createDefaultSavingsAccountData() {
    return SavingsAccountData.importInstanceIndividual(
        CLIENT_ID,
        ACCOUNT_ID,
        null,
        LocalDate.of(2026, 1, 1),
        BigDecimal.ONE,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        java.util.List.<org.apache.fineract.portfolio.savings.data.SavingsAccountChargeData>of(),
        false,
        null,
        null,
        null);
  }

  @BeforeEach
  void setUp() {
    resource =
        new SelfSavingsAccountApiResource(
            context,
            savingsAccountsApiResource,
            savingsAccountChargesApiResource,
            savingsAccountTransactionsApiResource,
            appuserSavingsMapperReadService,
            dataValidator,
            appUserClientMapperReadService);
  }

  private void mockAuthenticatedUser() {
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getId()).thenReturn(USER_ID);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }

  private void mockSavingsMapped() {
    mockAuthenticatedUser();
    when(appuserSavingsMapperReadService.isSavingsMappedToUser(ACCOUNT_ID, USER_ID))
        .thenReturn(true);
  }

  private void mockSavingsNotMapped() {
    mockAuthenticatedUser();
    when(appuserSavingsMapperReadService.isSavingsMappedToUser(ACCOUNT_ID, USER_ID))
        .thenReturn(false);
  }

  private void mockClientMapped() {
    mockAuthenticatedUser();
    when(appUserClientMapperReadService.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID))
        .thenReturn(true);
  }

  private void mockClientNotMapped() {
    mockAuthenticatedUser();
    when(appUserClientMapperReadService.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID))
        .thenReturn(false);
  }

  // --- retrieveSavings ---

  @Test
  void retrieveSavings_mappedAccount_returnsData() {
    mockSavingsMapped();
    SavingsAccountData data = createDefaultSavingsAccountData();
    when(savingsAccountsApiResource.retrieveOne(
            eq(ACCOUNT_ID), eq(false), eq("all"), isNull(), eq(uriInfo)))
        .thenReturn(data);

    SavingsAccountData result = resource.retrieveSavings(ACCOUNT_ID, "all", null, null, null, uriInfo);

    assertNotNull(result);
    verify(dataValidator).validateRetrieveSavings(uriInfo);
    //TODO
    /*verify(savingsAccountsApiResource)
    .retrieveOne(eq(ACCOUNT_ID), eq(false), eq("all"), eq(""), eq(uriInfo));*/
  }

  @Test
  void retrieveSavings_unmappedAccount_throws() {
    mockSavingsNotMapped();

    assertThrows(
        SavingsAccountNotFoundException.class,
        () -> resource.retrieveSavings(ACCOUNT_ID, "all", null, null, null, uriInfo));
  }

  // --- retrieveSavingsTransaction ---

  @Test
  void retrieveSavingsTransaction_mappedAccount_returnsData() {
    mockSavingsMapped();
    when(savingsAccountTransactionsApiResource.retrieveOne(ACCOUNT_ID, 99L, uriInfo))
        .thenReturn("{}");

    String result = resource.retrieveSavingsTransaction(ACCOUNT_ID, 99L, uriInfo);

    assertNotNull(result);
    verify(dataValidator).validateRetrieveSavingsTransaction(uriInfo);
    verify(savingsAccountTransactionsApiResource).retrieveOne(ACCOUNT_ID, 99L, uriInfo);
  }

  @Test
  void retrieveSavingsTransaction_unmappedAccount_throws() {
    mockSavingsNotMapped();

    assertThrows(
        SavingsAccountNotFoundException.class,
        () -> resource.retrieveSavingsTransaction(ACCOUNT_ID, 99L, uriInfo));
  }

  // --- retrieveAllSavingsAccountCharges ---

  @Test
  void retrieveAllSavingsAccountCharges_mappedAccount_returnsData() {
    mockSavingsMapped();
    when(savingsAccountChargesApiResource.retrieveAllSavingsAccountCharges(
            ACCOUNT_ID, "all", uriInfo))
        .thenReturn("[]");

    String result = resource.retrieveAllSavingsAccountCharges(ACCOUNT_ID, "all", uriInfo);

    assertNotNull(result);
    verify(savingsAccountChargesApiResource)
        .retrieveAllSavingsAccountCharges(ACCOUNT_ID, "all", uriInfo);
  }

  @Test
  void retrieveAllSavingsAccountCharges_unmappedAccount_throws() {
    mockSavingsNotMapped();

    assertThrows(
        SavingsAccountNotFoundException.class,
        () -> resource.retrieveAllSavingsAccountCharges(ACCOUNT_ID, "all", uriInfo));
  }

  // --- retrieveSavingsAccountCharge ---

  @Test
  void retrieveSavingsAccountCharge_mappedAccount_returnsData() {
    mockSavingsMapped();
    when(savingsAccountChargesApiResource.retrieveSavingsAccountCharge(ACCOUNT_ID, 50L, uriInfo))
        .thenReturn("{}");

    String result = resource.retrieveSavingsAccountCharge(ACCOUNT_ID, 50L, uriInfo);

    assertNotNull(result);
    verify(savingsAccountChargesApiResource).retrieveSavingsAccountCharge(ACCOUNT_ID, 50L, uriInfo);
  }

  @Test
  void retrieveSavingsAccountCharge_unmappedAccount_throws() {
    mockSavingsNotMapped();

    assertThrows(
        SavingsAccountNotFoundException.class,
        () -> resource.retrieveSavingsAccountCharge(ACCOUNT_ID, 50L, uriInfo));
  }

  // --- template ---

  @Test
  void template_mappedClient_returnsData() {
    mockClientMapped();
    when(savingsAccountsApiResource.template(CLIENT_ID, null, 15L, false, uriInfo))
        .thenReturn("{}");

    String result = resource.template(CLIENT_ID, 15L, uriInfo);

    assertNotNull(result);
    verify(savingsAccountsApiResource).template(CLIENT_ID, null, 15L, false, uriInfo);
  }

  @Test
  void template_unmappedClient_throws() {
    mockClientNotMapped();

    assertThrows(ClientNotFoundException.class, () -> resource.template(CLIENT_ID, 15L, uriInfo));
  }

  // --- submitSavingsAccountApplication ---

  @Test
  void submitSavingsAccountApplication_mappedClient_returnsData() {
    mockClientMapped();
    HashMap<String, Object> map = new HashMap<>();
    map.put(SelfSavingsAccountConstants.clientIdParameterName, CLIENT_ID);
    when(dataValidator.validateSavingsApplication(any())).thenReturn(map);
    when(savingsAccountsApiResource.submitApplication("body")).thenReturn("{}");

    String result = resource.submitSavingsAccountApplication("create", uriInfo, "body");

    assertNotNull(result);
    verify(savingsAccountsApiResource).submitApplication("body");
  }

  @Test
  void submitSavingsAccountApplication_unmappedClient_throws() {
    mockClientNotMapped();
    HashMap<String, Object> map = new HashMap<>();
    map.put(SelfSavingsAccountConstants.clientIdParameterName, CLIENT_ID);
    when(dataValidator.validateSavingsApplication(any())).thenReturn(map);

    assertThrows(
        ClientNotFoundException.class,
        () -> resource.submitSavingsAccountApplication("create", uriInfo, "body"));
    verify(savingsAccountsApiResource, never()).submitApplication(any());
  }

  // --- modifySavingsAccountApplication ---

  @Test
  void modifySavingsAccountApplication_mappedAccount_returnsData() {
    mockSavingsMapped();
    when(savingsAccountsApiResource.update(ACCOUNT_ID, "body", "update")).thenReturn("{}");

    String result = resource.modifySavingsAccountApplication(ACCOUNT_ID, "update", "body");

    assertNotNull(result);
    verify(dataValidator).validateSavingsApplication("body");
    verify(savingsAccountsApiResource).update(ACCOUNT_ID, "body", "update");
  }

  @Test
  void modifySavingsAccountApplication_unmappedAccount_throws() {
    mockSavingsNotMapped();

    assertThrows(
        SavingsAccountNotFoundException.class,
        () -> resource.modifySavingsAccountApplication(ACCOUNT_ID, "update", "body"));
    verify(savingsAccountsApiResource, never()).update(anyLong(), any(), any());
  }
}
