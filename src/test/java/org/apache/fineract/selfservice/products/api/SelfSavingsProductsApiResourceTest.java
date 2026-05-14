/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.products.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.portfolio.savings.data.SavingsProductData;
import org.apache.fineract.portfolio.savings.service.SavingsProductReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfSavingsProductsApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext securityContext;
  @Mock private SavingsProductReadPlatformService savingsProductReadPlatformService;
  @Mock private DefaultToApiJsonSerializer<SavingsProductData> toApiJsonSerializer;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private UriInfo uriInfo;

  private SelfSavingsProductsApiResource resource;

  private static final Long PRODUCT_ID = 1L;

  private static SavingsProductData defaultSavingsProductData() {
    return SavingsProductData.template(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  @BeforeEach
  void setUp() {
    resource =
        new SelfSavingsProductsApiResource(
            securityContext,
            savingsProductReadPlatformService,
            toApiJsonSerializer,
            apiRequestParameterHelper);

    Mockito.lenient().when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
    Mockito.lenient()
        .when(apiRequestParameterHelper.process(any()))
        .thenReturn(mock(ApiRequestJsonSerializationSettings.class));
  }

  @Test
  void retrieveAll_authorized_callsReadService() {
    Collection<SavingsProductData> products = List.of(defaultSavingsProductData());
    when(savingsProductReadPlatformService.retrieveAll()).thenReturn(products);
    when(toApiJsonSerializer.serialize(any(), any(Collection.class))).thenReturn("[]");

    String result = resource.retrieveAll(uriInfo);

    assertEquals("[]", result);
    verify(securityContext).validateHasReadPermission("SAVINGSPRODUCT");
    verify(savingsProductReadPlatformService).retrieveAll();
    verify(toApiJsonSerializer).serialize(any(), eq(products));
  }

  @Test
  void retrieveAll_unauthorized_throwsException() {
    doThrow(new NoAuthorizationException("User has no authority to READ savingsproducts"))
        .when(securityContext)
        .validateHasReadPermission("SAVINGSPRODUCT");

    assertThrows(NoAuthorizationException.class, () -> resource.retrieveAll(uriInfo));
    verifyNoInteractions(savingsProductReadPlatformService);
  }

  @Test
  void retrieveOne_authorized_callsReadService() {
    SavingsProductData product = defaultSavingsProductData();
    when(savingsProductReadPlatformService.retrieveOne(PRODUCT_ID)).thenReturn(product);
    when(toApiJsonSerializer.serialize(any(), any(SavingsProductData.class))).thenReturn("{}");

    String result = resource.retrieveOne(PRODUCT_ID, uriInfo);

    assertEquals("{}", result);
    verify(securityContext).validateHasReadPermission("SAVINGSPRODUCT");
    verify(savingsProductReadPlatformService).retrieveOne(PRODUCT_ID);
    verify(toApiJsonSerializer).serialize(any(), eq(product));
  }

  @Test
  void retrieveOne_unauthorized_throwsException() {
    doThrow(new NoAuthorizationException("User has no authority to READ savingsproducts"))
        .when(securityContext)
        .validateHasReadPermission("SAVINGSPRODUCT");

    assertThrows(NoAuthorizationException.class, () -> resource.retrieveOne(PRODUCT_ID, uriInfo));
    verifyNoInteractions(savingsProductReadPlatformService);
  }

  @Test
  void noCoreApiResourceInjected() {
    boolean hasCoreApiResource =
        Arrays.stream(SelfSavingsProductsApiResource.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(t -> t.getSimpleName().equals("SavingsProductsApiResource"));
    assertTrue(
        !hasCoreApiResource,
        "SelfSavingsProductsApiResource must not depend on core SavingsProductsApiResource");
  }
}
