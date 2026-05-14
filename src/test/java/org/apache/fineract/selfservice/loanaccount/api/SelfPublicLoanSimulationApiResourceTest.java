/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.exception.ResourceNotFoundException;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTemplateTypeRequiredException;
import org.apache.fineract.portfolio.loanaccount.exception.NotSupportedLoanTemplateTypeException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanScheduleData;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleAssembler;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanProductData;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanSimulationDataValidator;
import org.apache.fineract.selfservice.loanaccount.service.SelfPublicLoanProductReadService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfPublicLoanSimulationApiResourceTest {

  @Mock private SelfPublicLoanProductReadService loanProductReadService;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private SelfPublicLoanSimulationDataValidator dataValidator;
  @Mock private LoanScheduleAssembler loanScheduleAssembler;
  @Mock private DefaultToApiJsonSerializer<LoanScheduleData> loanScheduleSerializer;
  @Mock private FromJsonHelper fromJsonHelper;
  @Mock private UriInfo uriInfo;

  private SelfPublicLoanSimulationApiResource resource;

  @BeforeEach
  void setUp() {
    resource =
        new SelfPublicLoanSimulationApiResource(
            loanProductReadService,
            apiRequestParameterHelper,
            dataValidator,
            loanScheduleAssembler,
            loanScheduleSerializer,
            fromJsonHelper);

    Mockito.lenient().when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
    Mockito.lenient()
        .when(apiRequestParameterHelper.process(any()))
        .thenReturn(mock(ApiRequestJsonSerializationSettings.class));
  }

  private static SelfPublicLoanProductData sampleProduct(Long id) {
    return SelfPublicLoanProductData.builder()
        .id(id)
        .name("Test Loan " + id)
        .shortName("TL" + id)
        .currencyCode("USD")
        .principal(BigDecimal.valueOf(10000))
        .interestRatePerPeriod(BigDecimal.valueOf(12))
        .numberOfRepayments(12)
        .build();
  }

  // === Products endpoint tests ===

  @Test
  void retrieveAllLoanProducts_delegatesToPublicReadService() {
    Collection<SelfPublicLoanProductData> products = List.of(sampleProduct(1L));
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(products);

    String result = resource.retrieveAllLoanProducts();

    assertNotNull(result);
    verify(loanProductReadService).retrieveAllActiveLoanProducts();
  }

  @Test
  void retrieveAllLoanProducts_returnsSerialized() {
    Collection<SelfPublicLoanProductData> products = List.of(sampleProduct(1L));
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(products);

    String result = resource.retrieveAllLoanProducts();

    assertNotNull(result);
    assertTrue(result.contains("\"id\""));
    assertTrue(result.contains("\"principal\""));
  }

  // === Template endpoint tests ===

  @Test
  void template_withProductId_delegatesToReadService() {
    SelfPublicLoanProductData product = sampleProduct(1L);
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(List.of(product));

    String result = resource.template(1L, "individual");

    assertNotNull(result);
    verify(loanProductReadService).retrieveAllActiveLoanProducts();
    assertTrue(result.contains("\"id\":1"));
  }

  @Test
  void template_withoutProductId_returnsAllProducts() {
    Collection<SelfPublicLoanProductData> products = List.of(sampleProduct(1L));
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(products);

    String result = resource.template(null, "individual");

    assertNotNull(result);
    verify(loanProductReadService).retrieveAllActiveLoanProducts();
  }

  @Test
  void template_withUnknownProductId_throws404() {
    Collection<SelfPublicLoanProductData> products = List.of(sampleProduct(1L));
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(products);

    assertThrows(ResourceNotFoundException.class, () -> resource.template(999L, "individual"));
  }

  @Test
  void template_nullTemplateType_throws() {
    assertThrows(LoanTemplateTypeRequiredException.class, () -> resource.template(null, null));
    verifyNoInteractions(loanProductReadService);
  }

  @Test
  void template_invalidTemplateType_throws() {
    assertThrows(
        NotSupportedLoanTemplateTypeException.class, () -> resource.template(null, "invalid"));
    verifyNoInteractions(loanProductReadService);
  }

  @Test
  void template_collateralType_throws() {
    assertThrows(
        NotSupportedLoanTemplateTypeException.class, () -> resource.template(null, "collateral"));
    verifyNoInteractions(loanProductReadService);
  }

  @Test
  void template_groupType_throws() {
    assertThrows(
        NotSupportedLoanTemplateTypeException.class, () -> resource.template(null, "group"));
    verifyNoInteractions(loanProductReadService);
  }

  @Test
  void template_jlgType_throws() {
    assertThrows(NotSupportedLoanTemplateTypeException.class, () -> resource.template(null, "jlg"));
    verifyNoInteractions(loanProductReadService);
  }

  @Test
  void template_caseInsensitiveIndividual_passes() {
    Collection<SelfPublicLoanProductData> products = List.of(sampleProduct(1L));
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(products);

    String result = resource.template(null, "Individual");

    assertNotNull(result);
    verify(loanProductReadService).retrieveAllActiveLoanProducts();
  }

  // === Calculate schedule endpoint tests ===

  @Test
  void calculateSchedule_validRequest_delegatesToAssembler() {
    String json = "{\"productId\": 1, \"principal\": 10000}";
    var jsonElement = mock(com.google.gson.JsonElement.class);
    when(fromJsonHelper.parse(json)).thenReturn(jsonElement);

    // LoanScheduleModel is a final class — cannot mock directly.
    // Stub to return null; assert NPE is thrown by .toData() call.
    when(loanScheduleAssembler.assembleLoanScheduleFrom(any(com.google.gson.JsonElement.class)))
        .thenReturn(null);

    assertThrows(
        NullPointerException.class,
        () -> resource.calculateLoanSchedule("calculateLoanSchedule", uriInfo, json));

    verify(dataValidator).validatePublicSimulationRequest("calculateLoanSchedule", json);
    verify(loanScheduleAssembler).assembleLoanScheduleFrom(any(com.google.gson.JsonElement.class));
  }

  @Test
  void calculateSchedule_invalidCommand_validatorThrows() {
    String json = "{\"productId\": 1}";
    doThrow(new UnrecognizedQueryParamException("command", "submit", "calculateLoanSchedule"))
        .when(dataValidator)
        .validatePublicSimulationRequest("submit", json);

    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> resource.calculateLoanSchedule("submit", uriInfo, json));
    verifyNoInteractions(loanScheduleAssembler);
  }

  @Test
  void calculateSchedule_nullCommand_validatorThrows() {
    String json = "{\"productId\": 1}";
    doThrow(new UnrecognizedQueryParamException("command", null, "calculateLoanSchedule"))
        .when(dataValidator)
        .validatePublicSimulationRequest(null, json);

    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> resource.calculateLoanSchedule(null, uriInfo, json));
    verifyNoInteractions(loanScheduleAssembler);
  }

  // === Structural safety tests ===

  @Test
  void noPlatformSecurityContextInjected() {
    boolean hasSecurityContext =
        Arrays.stream(SelfPublicLoanSimulationApiResource.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(t -> t == PlatformSelfServiceSecurityContext.class);
    assertFalse(
        hasSecurityContext,
        "SelfPublicLoanSimulationApiResource must not depend on PlatformSelfServiceSecurityContext");
  }

  @Test
  void noAppuserLoansMapperInjected() {
    boolean hasMapper =
        Arrays.stream(SelfPublicLoanSimulationApiResource.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(t -> t.getSimpleName().equals("AppuserLoansMapperReadService"));
    assertFalse(
        hasMapper,
        "SelfPublicLoanSimulationApiResource must not depend on AppuserLoansMapperReadService");
  }

  @Test
  void noLoansApiResourceInjected() {
    boolean hasLoansApiResource =
        Arrays.stream(SelfPublicLoanSimulationApiResource.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(t -> t.getSimpleName().equals("LoansApiResource"));
    assertFalse(
        hasLoansApiResource,
        "SelfPublicLoanSimulationApiResource must not depend on core LoansApiResource");
  }
}
