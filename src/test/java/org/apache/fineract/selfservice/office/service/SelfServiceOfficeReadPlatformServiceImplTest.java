/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.apache.fineract.selfservice.office.data.OfficeDetailsData;
import org.apache.fineract.selfservice.office.data.OfficeGeolocationData;
import org.apache.fineract.selfservice.office.data.OfficeServiceData;
import org.apache.fineract.selfservice.office.data.SelfOfficeAddressData;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SelfServiceOfficeReadPlatformServiceImplTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private PlatformSelfServiceSecurityContext context;

  private SelfServiceOfficeReadPlatformServiceImpl service;

  private static final Long OFFICE_ID = 1L;
  private static final String HIERARCHY = ".";

  @BeforeEach
  void setUp() {
    service = new SelfServiceOfficeReadPlatformServiceImpl(jdbcTemplate, context);
  }

  private void mockAuthenticatedUser() {
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    Office office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(HIERARCHY);
    when(user.getOffice()).thenReturn(office);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }

  private void mockOfficeExistsInHierarchy() {
    mockAuthenticatedUser();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office o WHERE o.id = ? AND o.hierarchy LIKE ?"),
            eq(Integer.class),
            eq(OFFICE_ID),
            anyString()))
        .thenReturn(1);
  }

  private void mockOfficeNotInHierarchy() {
    mockAuthenticatedUser();
    when(jdbcTemplate.queryForObject(
            eq("SELECT 1 FROM m_office o WHERE o.id = ? AND o.hierarchy LIKE ?"),
            eq(Integer.class),
            eq(OFFICE_ID),
            anyString()))
        .thenThrow(new EmptyResultDataAccessException(1));
  }

  @Test
  void retrieveOfficeDetails_existsInHierarchy_returnsData() {
    mockOfficeExistsInHierarchy();
    OfficeDetailsData expected = OfficeDetailsData.instance(OFFICE_ID, "Head Office", "HO-001");
    when(jdbcTemplate.queryForObject(
            contains("o.name"), any(RowMapper.class), eq(OFFICE_ID), anyString()))
        .thenReturn(expected);

    OfficeDetailsData result = service.retrieveOfficeDetails(OFFICE_ID);

    assertNotNull(result);
    assertEquals("Head Office", result.getName());
  }

  @Test
  void retrieveOfficeDetails_notInHierarchy_throws() {
    mockOfficeNotInHierarchy();

    assertThrows(OfficeNotFoundException.class, () -> service.retrieveOfficeDetails(OFFICE_ID));
  }

  @Test
  void retrieveOfficeServices_existsInHierarchy_returnsList() {
    mockOfficeExistsInHierarchy();
    List<OfficeServiceData> expected =
        List.of(OfficeServiceData.instance(1L, "Loans", "SVC-001", "Mon-Fri 09:00-17:00"));
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(OFFICE_ID), anyString()))
        .thenReturn(expected);

    Collection<OfficeServiceData> result = service.retrieveOfficeServices(OFFICE_ID);

    assertNotNull(result);
    assertEquals(1, result.size());
  }

  @Test
  void retrieveOfficeServices_notInHierarchy_throws() {
    mockOfficeNotInHierarchy();

    assertThrows(OfficeNotFoundException.class, () -> service.retrieveOfficeServices(OFFICE_ID));
  }

  @Test
  void retrieveOfficeGeolocation_exists_returnsData() {
    mockOfficeExistsInHierarchy();
    OfficeGeolocationData expected =
        OfficeGeolocationData.instance(new BigDecimal("19.4326077"), new BigDecimal("-99.1332080"));
    when(jdbcTemplate.queryForObject(
            contains("latitude"), any(RowMapper.class), eq(OFFICE_ID), anyString()))
        .thenReturn(expected);

    OfficeGeolocationData result = service.retrieveOfficeGeolocation(OFFICE_ID);

    assertNotNull(result);
    assertEquals(new BigDecimal("19.4326077"), result.getLatitude());
  }

  @Test
  void retrieveOfficeGeolocation_noData_returnsNull() {
    mockOfficeExistsInHierarchy();
    when(jdbcTemplate.queryForObject(
            contains("latitude"), any(RowMapper.class), eq(OFFICE_ID), anyString()))
        .thenThrow(new EmptyResultDataAccessException(1));

    OfficeGeolocationData result = service.retrieveOfficeGeolocation(OFFICE_ID);

    assertNull(result);
  }

  @Test
  void retrieveOfficeGeolocation_notInHierarchy_throws() {
    mockOfficeNotInHierarchy();

    assertThrows(OfficeNotFoundException.class, () -> service.retrieveOfficeGeolocation(OFFICE_ID));
  }

  @Test
  void retrieveOfficeAddress_tableUnavailable_returnsNull() {
    mockOfficeExistsInHierarchy();

    SelfOfficeAddressData result = service.retrieveOfficeAddress(OFFICE_ID);

    assertNull(result);
  }

  @Test
  void retrieveOfficeAddress_notInHierarchy_throws() {
    mockOfficeNotInHierarchy();

    assertThrows(OfficeNotFoundException.class, () -> service.retrieveOfficeAddress(OFFICE_ID));
  }

  @Test
  void retrieveOfficeAddresses_singleRow_returnsAddressData() throws Exception {
    mockAuthenticatedUser();
    ReflectionTestUtils.setField(service, "officeAddressTableAvailable", true);

    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getLong("office_id")).thenReturn(OFFICE_ID);
    when(resultSet.getString("street")).thenReturn("Main Street");
    when(resultSet.getString("postal_code")).thenReturn("10001");
    when(resultSet.getString("city")).thenReturn("New York");
    when(resultSet.getString("state_province")).thenReturn("NY");
    when(resultSet.getString("country_name")).thenReturn("USA");

    doAnswer(
            invocation -> {
              RowCallbackHandler callback = invocation.getArgument(1);
              callback.processRow(resultSet);
              return null;
            })
        .when(jdbcTemplate)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

    Map<Long, SelfOfficeAddressData> result = service.retrieveOfficeAddresses(List.of(OFFICE_ID));

    assertNotNull(result.get(OFFICE_ID));
  }

  @Test
  void retrieveOfficeGeolocations_singleRow_returnsGeolocationData() throws Exception {
    mockAuthenticatedUser();

    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getLong("office_id")).thenReturn(OFFICE_ID);
    when(resultSet.getBigDecimal("latitude")).thenReturn(new BigDecimal("19.4326077"));
    when(resultSet.getBigDecimal("longitude")).thenReturn(new BigDecimal("-99.1332080"));

    doAnswer(
            invocation -> {
              RowCallbackHandler callback = invocation.getArgument(1);
              callback.processRow(resultSet);
              return null;
            })
        .when(jdbcTemplate)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

    Map<Long, OfficeGeolocationData> result =
        service.retrieveOfficeGeolocations(List.of(OFFICE_ID));

    assertNotNull(result.get(OFFICE_ID));
    assertEquals(new BigDecimal("19.4326077"), result.get(OFFICE_ID).getLatitude());
    assertEquals(new BigDecimal("-99.1332080"), result.get(OFFICE_ID).getLongitude());
  }

  @Test
  void isOfficeAddressTableAvailable_defaultFalse() {
    boolean result = service.isOfficeAddressTableAvailable();
    assertFalse(result);
  }
}