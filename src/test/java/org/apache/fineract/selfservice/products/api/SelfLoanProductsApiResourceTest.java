/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.products.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.portfolio.loanproduct.data.LoanProductData;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfLoanProductsApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext securityContext;
  @Mock private LoanProductReadPlatformService loanProductReadPlatformService;
  @Mock private DefaultToApiJsonSerializer<LoanProductData> toApiJsonSerializer;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private UriInfo uriInfo;

  private SelfLoanProductsApiResource resource;

  private static final Long PRODUCT_ID = 1L;

  @BeforeEach
  void setUp() {
    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);

    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 11, 5));
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 11, 4));
    ThreadLocalContextUtil.setBusinessDates(businessDates);

    resource =
        new SelfLoanProductsApiResource(
            securityContext,
            loanProductReadPlatformService,
            toApiJsonSerializer,
            apiRequestParameterHelper);

    Mockito.lenient().when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
    Mockito.lenient()
        .when(apiRequestParameterHelper.process(any()))
        .thenReturn(mock(ApiRequestJsonSerializationSettings.class));
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void retrieveAllLoanProducts_authorized_callsReadService() {
    Collection<LoanProductData> products = List.of(mock(LoanProductData.class));
    when(loanProductReadPlatformService.retrieveAllLoanProducts()).thenReturn(products);
    when(toApiJsonSerializer.serialize(any(), any(Collection.class))).thenReturn("[]");

    String result = resource.retrieveAllLoanProducts(uriInfo);

    assertNotNull(result);
    verify(securityContext).validateHasReadPermission("LOANPRODUCT");
    verify(loanProductReadPlatformService).retrieveAllLoanProducts();
  }

  @Test
  void retrieveAllLoanProducts_returnsOnlyActiveProducts() {
    LocalDate today = DateUtils.getBusinessLocalDate();

    LoanProductData active = mock(LoanProductData.class);
    when(active.getStartDate()).thenReturn(today.minusDays(5));
    when(active.getCloseDate()).thenReturn(today.plusDays(5));

    LoanProductData future = mock(LoanProductData.class);
    when(future.getStartDate()).thenReturn(today.plusDays(1));
    LoanProductData expired = mock(LoanProductData.class);
    when(expired.getStartDate()).thenReturn(null);
    when(expired.getCloseDate()).thenReturn(today.minusDays(1));

    LoanProductData boundary = mock(LoanProductData.class);
    when(boundary.getStartDate()).thenReturn(today);
    when(boundary.getCloseDate()).thenReturn(today);

    when(loanProductReadPlatformService.retrieveAllLoanProducts())
        .thenReturn(List.of(active, future, expired, boundary));

    when(toApiJsonSerializer.serialize(any(), any(Collection.class))).thenReturn("[]");

    resource.retrieveAllLoanProducts(uriInfo);

    ArgumentCaptor<Collection<LoanProductData>> captor = ArgumentCaptor.forClass(Collection.class);

    verify(toApiJsonSerializer).serialize(any(), captor.capture());

    assertEquals(List.of(active, boundary), List.copyOf(captor.getValue()));
  }

  @Test
  void retrieveLoanProductDetails_authorized_callsReadService() {
    LoanProductData product = mock(LoanProductData.class);
    when(loanProductReadPlatformService.retrieveLoanProduct(PRODUCT_ID)).thenReturn(product);
    when(toApiJsonSerializer.serialize(any(), any(LoanProductData.class))).thenReturn("{}");

    String result = resource.retrieveLoanProductDetails(PRODUCT_ID, uriInfo);

    assertNotNull(result);
    verify(securityContext).validateHasReadPermission("LOANPRODUCT");
    verify(loanProductReadPlatformService).retrieveLoanProduct(PRODUCT_ID);
  }

  @Test
  void retrieveLoanProductDetails_unauthorized_throwsException() {
    doThrow(new NoAuthorizationException("User has no authority to READ loanproducts"))
        .when(securityContext)
        .validateHasReadPermission("LOANPRODUCT");

    assertThrows(
        NoAuthorizationException.class,
        () -> resource.retrieveLoanProductDetails(PRODUCT_ID, uriInfo));
    verifyNoInteractions(loanProductReadPlatformService);
  }

  @Test
  void noCoreApiResourceInjected() {
    boolean hasCoreApiResource =
        Arrays.stream(SelfLoanProductsApiResource.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(t -> t.getSimpleName().equals("LoanProductsApiResource"));
    assertTrue(
        !hasCoreApiResource,
        "SelfLoanProductsApiResource must not depend on core LoanProductsApiResource");
  }
}
