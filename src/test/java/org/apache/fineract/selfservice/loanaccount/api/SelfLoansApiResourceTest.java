/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.api.LoanChargesApiResource;
import org.apache.fineract.portfolio.loanaccount.api.LoanTransactionsApiResource;
import org.apache.fineract.portfolio.loanaccount.api.LoansApiResource;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTemplateTypeRequiredException;
import org.apache.fineract.portfolio.loanaccount.exception.NotSupportedLoanTemplateTypeException;
import org.apache.fineract.portfolio.loanaccount.guarantor.api.GuarantorsApiResource;
import org.apache.fineract.portfolio.loanaccount.guarantor.data.GuarantorData;
import org.apache.fineract.selfservice.client.service.AppSelfServiceUserClientMapperReadService;
import org.apache.fineract.selfservice.loanaccount.data.SelfLoansDataValidator;
import org.apache.fineract.selfservice.loanaccount.service.AppuserLoansMapperReadService;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfLoansApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private LoansApiResource loansApiResource;
  @Mock private LoanTransactionsApiResource loanTransactionsApiResource;
  @Mock private LoanChargesApiResource loanChargesApiResource;
  @Mock private AppuserLoansMapperReadService appuserLoansMapperReadService;
  @Mock private AppSelfServiceUserClientMapperReadService appUserClientMapperReadService;
  @Mock private SelfLoansDataValidator dataValidator;
  @Mock private GuarantorsApiResource guarantorsApiResource;
  @Mock private UriInfo uriInfo;

  // NEW MOCKS for notification dependencies
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private Environment env;
  @Mock private HttpServletRequest httpRequest;
  @Mock private SelfServiceOwnershipGuard ownershipGuard;

  private SelfLoansApiResource resource;

  private static final Long USER_ID = 10L;
  private static final Long LOAN_ID = 5L;
  private static final Long CLIENT_ID = 7L;

  @BeforeEach
  void setUp() {
    resource =
        new SelfLoansApiResource(
            context,
            loansApiResource,
            loanTransactionsApiResource,
            loanChargesApiResource,
            appuserLoansMapperReadService,
            appUserClientMapperReadService,
            dataValidator,
            guarantorsApiResource,
            applicationEventPublisher, // NEW
            env,
            ownershipGuard); // NEW
  }

  private void mockAuthenticatedUser() {
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getId()).thenReturn(USER_ID);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }

  private void mockLoanMapped() {
    mockAuthenticatedUser();
    when(appuserLoansMapperReadService.isLoanMappedToUser(LOAN_ID, USER_ID)).thenReturn(true);
  }

  private void mockLoanNotMapped() {
    mockAuthenticatedUser();
    when(appuserLoansMapperReadService.isLoanMappedToUser(LOAN_ID, USER_ID)).thenReturn(false);
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

  // --- retrieveLoan ---

  @Test
  void retrieveLoan_mappedLoan_returnsData() {
    mockLoanMapped();
    when(loansApiResource.retrieveLoan(
            eq(LOAN_ID),
            eq(false),
            eq(LoanApiConstants.LOAN_ASSOCIATIONS_ALL),
            eq(null),
            eq(null),
            eq(uriInfo)))
        .thenReturn("{}");

    String result = resource.retrieveLoan(LOAN_ID, uriInfo);

    assertNotNull(result);
    verify(dataValidator).validateRetrieveLoan(uriInfo);
    verify(loansApiResource)
        .retrieveLoan(LOAN_ID, false, LoanApiConstants.LOAN_ASSOCIATIONS_ALL, null, null, uriInfo);
  }

  @Test
  void retrieveLoan_unmappedLoan_throws() {
    mockLoanNotMapped();

    assertThrows(LoanNotFoundException.class, () -> resource.retrieveLoan(LOAN_ID, uriInfo));
  }

  // --- retrieveTransaction ---

  @Test
  void retrieveTransaction_mappedLoan_returnsData() {
    mockLoanMapped();
    when(loanTransactionsApiResource.retrieveTransaction(LOAN_ID, 99L, "fields", uriInfo))
        .thenReturn("{}");

    String result = resource.retrieveTransaction(LOAN_ID, 99L, "fields", uriInfo);

    assertNotNull(result);
    verify(dataValidator).validateRetrieveTransaction(uriInfo);
    verify(loanTransactionsApiResource).retrieveTransaction(LOAN_ID, 99L, "fields", uriInfo);
  }

  @Test
  void retrieveTransaction_unmappedLoan_throws() {
    mockLoanNotMapped();

    assertThrows(
        LoanNotFoundException.class,
        () -> resource.retrieveTransaction(LOAN_ID, 99L, "fields", uriInfo));
  }

  // --- retrieveAllLoanCharges ---

  @Test
  void retrieveAllLoanCharges_mappedLoan_returnsData() {
    mockLoanMapped();
    when(loanChargesApiResource.retrieveAllLoanCharges(LOAN_ID, uriInfo)).thenReturn("[]");

    String result = resource.retrieveAllLoanCharges(LOAN_ID, uriInfo);

    assertNotNull(result);
    verify(loanChargesApiResource).retrieveAllLoanCharges(LOAN_ID, uriInfo);
  }

  @Test
  void retrieveAllLoanCharges_unmappedLoan_throws() {
    mockLoanNotMapped();

    assertThrows(
        LoanNotFoundException.class, () -> resource.retrieveAllLoanCharges(LOAN_ID, uriInfo));
  }

  // --- retrieveLoanCharge ---

  @Test
  void retrieveLoanCharge_mappedLoan_returnsData() {
    mockLoanMapped();
    when(loanChargesApiResource.retrieveLoanCharge(LOAN_ID, 50L, uriInfo)).thenReturn("{}");

    String result = resource.retrieveLoanCharge(LOAN_ID, 50L, uriInfo);

    assertNotNull(result);
    verify(loanChargesApiResource).retrieveLoanCharge(LOAN_ID, 50L, uriInfo);
  }

  @Test
  void retrieveLoanCharge_unmappedLoan_throws() {
    mockLoanNotMapped();

    assertThrows(
        LoanNotFoundException.class, () -> resource.retrieveLoanCharge(LOAN_ID, 50L, uriInfo));
  }

  // --- template ---

  @Test
  void template_mappedClient_returnsData() {
    mockClientMapped();
    when(loansApiResource.template(CLIENT_ID, null, 15L, "individual", false, true, uriInfo))
        .thenReturn("{}");

    String result = resource.template(CLIENT_ID, 15L, "individual", uriInfo);

    assertNotNull(result);
    verify(loansApiResource).template(CLIENT_ID, null, 15L, "individual", false, true, uriInfo);
  }

  @Test
  void template_unmappedClient_throws() {
    mockClientNotMapped();

    assertThrows(
        ClientNotFoundException.class,
        () -> resource.template(CLIENT_ID, 15L, "individual", uriInfo));
  }

  @Test
  void template_nullTemplateType_throws() {
    mockClientMapped();

    assertThrows(
        LoanTemplateTypeRequiredException.class,
        () -> resource.template(CLIENT_ID, 15L, null, uriInfo));
    verify(loansApiResource, never())
        .template(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void template_invalidTemplateType_throws() {
    mockClientMapped();

    assertThrows(
        NotSupportedLoanTemplateTypeException.class,
        () -> resource.template(CLIENT_ID, 15L, "invalid", uriInfo));
    verify(loansApiResource, never())
        .template(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
  }

  // --- calculateLoanScheduleOrSubmitLoanApplication ---

  @Test
  void calculateLoanScheduleOrSubmitLoanApplication_mappedClient_returnsData() {
    mockClientMapped();
    HashMap<String, Object> map = new HashMap<>();
    map.put("clientId", CLIENT_ID);
    when(dataValidator.validateLoanApplication(any())).thenReturn(map);
    when(loansApiResource.calculateLoanScheduleOrSubmitLoanApplication("submit", uriInfo, "body"))
        .thenReturn("{}");

    // UPDATED: Added httpRequest parameter
    String result =
        resource.calculateLoanScheduleOrSubmitLoanApplication(
            "submit", uriInfo, "body", httpRequest);

    assertNotNull(result);
    verify(loansApiResource)
        .calculateLoanScheduleOrSubmitLoanApplication("submit", uriInfo, "body");
  }

  @Test
  void calculateLoanScheduleOrSubmitLoanApplication_unmappedClient_throws() {
    mockClientNotMapped();
    HashMap<String, Object> map = new HashMap<>();
    map.put("clientId", CLIENT_ID);
    when(dataValidator.validateLoanApplication(any())).thenReturn(map);

    // UPDATED: Added httpRequest parameter
    assertThrows(
        ClientNotFoundException.class,
        () ->
            resource.calculateLoanScheduleOrSubmitLoanApplication(
                "submit", uriInfo, "body", httpRequest));
    verify(loansApiResource, never())
        .calculateLoanScheduleOrSubmitLoanApplication(any(), any(), any());
  }

  // --- modifyLoanApplication ---

  @Test
  void modifyLoanApplication_mappedLoanAndClient_returnsData() {
    mockLoanMapped();
    when(appUserClientMapperReadService.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID))
        .thenReturn(true);
    HashMap<String, Object> map = new HashMap<>();
    map.put("clientId", CLIENT_ID);
    when(dataValidator.validateModifyLoanApplication(any())).thenReturn(map);
    when(loansApiResource.modifyLoanApplication(eq(LOAN_ID), eq((String) null), eq("body")))
        .thenReturn("{}");

    // UPDATED: Added httpRequest parameter
    String result = resource.modifyLoanApplication(LOAN_ID, "body", httpRequest);

    assertNotNull(result);
    verify(loansApiResource).modifyLoanApplication(eq(LOAN_ID), eq((String) null), eq("body"));
  }

  @Test
  void modifyLoanApplication_unmappedLoan_throws() {
    HashMap<String, Object> map = new HashMap<>();
    map.put("clientId", CLIENT_ID);
    when(dataValidator.validateModifyLoanApplication(any())).thenReturn(map);
    mockLoanNotMapped();

    // UPDATED: Added httpRequest parameter
    assertThrows(
        LoanNotFoundException.class,
        () -> resource.modifyLoanApplication(LOAN_ID, "body", httpRequest));
    verify(loansApiResource, never()).modifyLoanApplication(any(Long.class), any(), any());
  }

  // --- stateTransitions ---

  @Test
  void stateTransitions_withdrawnByApplicant_mappedLoan_returnsData() {
    mockLoanMapped();
    when(loansApiResource.stateTransitions(eq(LOAN_ID), eq("withdrawnByApplicant"), eq("body")))
        .thenReturn("{}");

    // UPDATED: Added httpRequest parameter
    String result = resource.stateTransitions(LOAN_ID, "withdrawnByApplicant", "body", httpRequest);

    assertNotNull(result);
    verify(loansApiResource).stateTransitions(eq(LOAN_ID), eq("withdrawnByApplicant"), eq("body"));
  }

  @Test
  void stateTransitions_invalidCommand_throws() {
    // UPDATED: Added httpRequest parameter
    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> resource.stateTransitions(LOAN_ID, "approve", "body", httpRequest));
    verify(loansApiResource, never()).stateTransitions(any(Long.class), any(), any());
  }

  // --- retrieveGuarantors ---

  @Test
  void retrieveGuarantors_mappedLoan_returnsData() {
    mockLoanMapped();
    List<GuarantorData> data = List.of(mock(GuarantorData.class));
    when(guarantorsApiResource.retrieveGuarantorDetails(LOAN_ID)).thenReturn(data);

    List<GuarantorData> result = resource.retrieveGuarantorDetails(LOAN_ID, uriInfo);

    assertNotNull(result);
    assertEquals(1, result.size());
    verify(guarantorsApiResource).retrieveGuarantorDetails(LOAN_ID);
  }

  @Test
  void retrieveGuarantors_unmappedLoan_throws() {
    mockLoanNotMapped();

    assertThrows(
        LoanNotFoundException.class, () -> resource.retrieveGuarantorDetails(LOAN_ID, uriInfo));
  }
}
