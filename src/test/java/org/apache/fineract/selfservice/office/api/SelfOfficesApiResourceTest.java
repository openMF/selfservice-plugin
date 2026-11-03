/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.selfservice.office.data.OfficeDetailsData;
import org.apache.fineract.selfservice.office.data.OfficeGeolocationData;
import org.apache.fineract.selfservice.office.data.OfficeServiceData;
import org.apache.fineract.selfservice.office.data.SelfOfficeAddressData;
import org.apache.fineract.selfservice.office.service.SelfServiceOfficeReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfOfficesApiResourceTest {

  @Mock private SelfOfficeSwaggerMapper officeSwaggerMapper;
  @Mock private PlatformSecurityContext context;
  @Mock private OfficeReadPlatformService readPlatformService;
  @Mock private DefaultToApiJsonSerializer<OfficeData> toApiJsonSerializer;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private SqlValidator sqlValidator;
  @Mock private SelfServiceOfficeReadPlatformService selfServiceOfficeReadPlatformService;

  private SelfOfficesApiResource resource;

  private static final Long OFFICE_ID = 1L;

  @BeforeEach
  void setUp() {
    resource =
        new SelfOfficesApiResource(
            officeSwaggerMapper,
            context,
            readPlatformService,
            toApiJsonSerializer,
            apiRequestParameterHelper,
            sqlValidator,
            selfServiceOfficeReadPlatformService);
  }

  private void mockAuthenticatedUserWithPermission() {
    AppUser user = mock(AppUser.class);
    when(context.authenticatedUser()).thenReturn(user);
  }

  @Test
  void retrieveOfficeDetails_validId_returnsData() {
    mockAuthenticatedUserWithPermission();
    OfficeDetailsData data = OfficeDetailsData.instance(OFFICE_ID, "Head Office", "HO-001");
    when(selfServiceOfficeReadPlatformService.retrieveOfficeDetails(OFFICE_ID)).thenReturn(data);
    when(toApiJsonSerializer.serializeResult(data)).thenReturn("{}");

    String result = resource.retrieveOfficeDetails(OFFICE_ID);

    assertNotNull(result);
    verify(selfServiceOfficeReadPlatformService).retrieveOfficeDetails(OFFICE_ID);
  }

  @Test
  void retrieveOfficeDetails_invalidId_throws() {
    mockAuthenticatedUserWithPermission();
    assertThrows(OfficeNotFoundException.class, () -> resource.retrieveOfficeDetails(-1L));
    verify(selfServiceOfficeReadPlatformService, never()).retrieveOfficeDetails(any());
  }

  @Test
  void retrieveOfficeDetails_nullId_throws() {
    mockAuthenticatedUserWithPermission();
    assertThrows(OfficeNotFoundException.class, () -> resource.retrieveOfficeDetails(null));
  }

  @Test
  void retrieveOfficeServices_validId_returnsList() {
    mockAuthenticatedUserWithPermission();
    List<OfficeServiceData> data =
        List.of(OfficeServiceData.instance(1L, "Loans", "SVC-001", "Mon-Fri 09:00-17:00"));
    when(selfServiceOfficeReadPlatformService.retrieveOfficeServices(OFFICE_ID)).thenReturn(data);
    when(toApiJsonSerializer.serializeResult(data)).thenReturn("[]");

    String result = resource.retrieveOfficeServices(OFFICE_ID);

    assertNotNull(result);
    verify(selfServiceOfficeReadPlatformService).retrieveOfficeServices(OFFICE_ID);
  }

  @Test
  void retrieveOfficeServices_invalidId_throws() {
    mockAuthenticatedUserWithPermission();
    assertThrows(OfficeNotFoundException.class, () -> resource.retrieveOfficeServices(0L));
  }

  @Test
  void retrieveOfficeGeolocation_validId_returnsData() {
    mockAuthenticatedUserWithPermission();
    OfficeGeolocationData data =
        OfficeGeolocationData.instance(new BigDecimal("19.4326077"), new BigDecimal("-99.1332080"));
    when(selfServiceOfficeReadPlatformService.retrieveOfficeGeolocation(OFFICE_ID))
        .thenReturn(data);
    when(toApiJsonSerializer.serializeResult(data)).thenReturn("{}");

    String result = resource.retrieveOfficeGeolocation(OFFICE_ID);

    assertNotNull(result);
  }

  @Test
  void retrieveOfficeGeolocation_invalidId_throws() {
    mockAuthenticatedUserWithPermission();
    assertThrows(OfficeNotFoundException.class, () -> resource.retrieveOfficeGeolocation(-5L));
  }

  @Test
  void retrieveOfficeAddress_validId_returnsData() {
    mockAuthenticatedUserWithPermission();
    SelfOfficeAddressData data =
        SelfOfficeAddressData.instance("Av. Reforma 505", "06500", "CDMX", "CDMX", "Mexico");
    when(selfServiceOfficeReadPlatformService.retrieveOfficeAddress(OFFICE_ID)).thenReturn(data);
    when(toApiJsonSerializer.serializeResult(data)).thenReturn("{}");

    String result = resource.retrieveOfficeAddress(OFFICE_ID);

    assertNotNull(result);
  }

  @Test
  void retrieveOfficeAddress_invalidId_throws() {
    mockAuthenticatedUserWithPermission();
    assertThrows(OfficeNotFoundException.class, () -> resource.retrieveOfficeAddress(-1L));
  }

  @Test
  void retrieveOffices_includesAddressAndGeolocationByDefault() {
    mockAuthenticatedUserWithPermission();

    UriInfo uriInfo = mock(UriInfo.class);
    MultivaluedMap<String, String> queryParameters = mock(MultivaluedMap.class);
    ApiRequestJsonSerializationSettings settings =
        mock(ApiRequestJsonSerializationSettings.class);

    OfficeData office = mock(OfficeData.class);
    when(office.getId()).thenReturn(OFFICE_ID);

    when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
    when(queryParameters.getFirst("fields")).thenReturn(null);
    when(apiRequestParameterHelper.process(queryParameters)).thenReturn(settings);

    List<OfficeData> offices = List.of(office);

    when(readPlatformService.retrieveAllOffices(anyBoolean(), any(SearchParameters.class)))
        .thenReturn(offices);

    when(toApiJsonSerializer.serialize(any(), anyCollection(), anySet()))
        .thenReturn("[{\"id\":1,\"name\":\"Head Office\"}]");

    when(selfServiceOfficeReadPlatformService.retrieveOfficeAddresses(List.of(OFFICE_ID)))
        .thenReturn(
            Map.of(
                OFFICE_ID,
                SelfOfficeAddressData.instance(
                    "Av. Reforma 505", "06500", "CDMX", "CDMX", "Mexico")));

    when(selfServiceOfficeReadPlatformService.retrieveOfficeGeolocations(List.of(OFFICE_ID)))
        .thenReturn(
            Map.of(
                OFFICE_ID,
                OfficeGeolocationData.instance(
                    new BigDecimal("19.4326077"), new BigDecimal("-99.1332080"))));

    String result = resource.retrieveOffices(uriInfo, false, null, null);

    assertNotNull(result);
    assertTrue(result.contains("\"address\""));
    assertTrue(result.contains("\"geolocation\""));

    verify(selfServiceOfficeReadPlatformService)
        .retrieveOfficeAddresses(List.of(OFFICE_ID));
    verify(selfServiceOfficeReadPlatformService)
        .retrieveOfficeGeolocations(List.of(OFFICE_ID));
  }
}
