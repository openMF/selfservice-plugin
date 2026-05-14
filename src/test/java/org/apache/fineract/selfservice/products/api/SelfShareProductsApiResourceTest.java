/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.products.api;

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
import java.util.Arrays;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.portfolio.products.data.ProductData;
import org.apache.fineract.portfolio.products.service.ShareProductReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.useradministration.exception.UnAuthenticatedUserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfShareProductsApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext securityContext;
  @Mock private ShareProductReadPlatformService shareProductReadPlatformService;
  @Mock private DefaultToApiJsonSerializer<ProductData> toApiJsonSerializer;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private UriInfo uriInfo;

  private SelfShareProductsApiResource resource;

  private static final Long PRODUCT_ID = 1L;

  @BeforeEach
  void setUp() {
    resource =
        new SelfShareProductsApiResource(
            securityContext,
            shareProductReadPlatformService,
            toApiJsonSerializer,
            apiRequestParameterHelper);

    Mockito.lenient().when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
    Mockito.lenient()
        .when(apiRequestParameterHelper.process(any()))
        .thenReturn(mock(ApiRequestJsonSerializationSettings.class));
  }

  @Test
  void retrieveAllProducts_authenticated_callsReadService() {
    Page<ProductData> page = mock(Page.class);
    when(shareProductReadPlatformService.retrieveAllProducts(null, null)).thenReturn(page);
    when(toApiJsonSerializer.serialize(any(), any(Page.class))).thenReturn("[]");

    String result = resource.retrieveAllProducts(null, null, uriInfo);

    assertNotNull(result);
    // Share products only require an authenticated user, no specific permission check
    verify(securityContext).authenticatedSelfServiceUser();
    verify(shareProductReadPlatformService).retrieveAllProducts(null, null);
  }

  @Test
  void retrieveAllProducts_unauthenticated_throwsException() {
    doThrow(new UnAuthenticatedUserException())
        .when(securityContext)
        .authenticatedSelfServiceUser();

    assertThrows(
        UnAuthenticatedUserException.class,
        () -> resource.retrieveAllProducts(null, null, uriInfo));
    verifyNoInteractions(shareProductReadPlatformService);
  }

  @Test
  void retrieveProduct_authenticated_callsReadService() {
    ProductData product = mock(ProductData.class);
    when(shareProductReadPlatformService.retrieveOne(PRODUCT_ID, false)).thenReturn(product);
    when(toApiJsonSerializer.serialize(any(), any(ProductData.class))).thenReturn("{}");

    String result = resource.retrieveProduct(PRODUCT_ID, uriInfo);

    assertNotNull(result);
    verify(securityContext).authenticatedSelfServiceUser();
    verify(shareProductReadPlatformService).retrieveOne(PRODUCT_ID, false);
  }

  @Test
  void retrieveProduct_unauthenticated_throwsException() {
    doThrow(new UnAuthenticatedUserException())
        .when(securityContext)
        .authenticatedSelfServiceUser();

    assertThrows(
        UnAuthenticatedUserException.class, () -> resource.retrieveProduct(PRODUCT_ID, uriInfo));
    verifyNoInteractions(shareProductReadPlatformService);
  }

  @Test
  void noCoreApiResourceInjected() {
    boolean hasCoreApiResource =
        Arrays.stream(SelfShareProductsApiResource.class.getDeclaredFields())
            .map(Field::getType)
            .anyMatch(t -> t.getSimpleName().equals("ProductsApiResource"));
    assertTrue(
        !hasCoreApiResource,
        "SelfShareProductsApiResource must not depend on core ProductsApiResource");
  }
}
