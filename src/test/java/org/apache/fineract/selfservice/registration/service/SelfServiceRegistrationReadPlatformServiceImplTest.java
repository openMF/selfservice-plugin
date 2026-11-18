/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class SelfServiceRegistrationReadPlatformServiceImplTest {

  @Mock private JdbcTemplate jdbcTemplate;

  private SelfServiceRegistrationReadPlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new SelfServiceRegistrationReadPlatformServiceImpl(jdbcTemplate);
    lenient()
        .when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);
  }

  @Test
  void mobileModeWithMiddleName_bindsOneValuePerPlaceholder() {
    assertTrue(service.isClientExist("000000001", "Ana", "Maria", "Lopez", "88887777", false));

    assertQuery(
        " and middlename = ? and mobile_no = ?", "000000001", "Ana", "Lopez", "Maria", "88887777");
  }

  @Test
  void mobileModeWithoutMiddleName_matchesTheMobileNumber() {
    service.isClientExist("000000001", "Ana", null, "Lopez", "88887777", false);

    assertQuery(" and mobile_no = ?", "000000001", "Ana", "Lopez", "88887777");
  }

  @Test
  void emailModeWithMiddleName_matchesTheMiddleNameOnly() {
    service.isClientExist("000000001", "Ana", "Maria", "Lopez", null, true);

    assertQuery(" and middlename = ?", "000000001", "Ana", "Lopez", "Maria");
  }

  @Test
  void emailModeWithoutMiddleName_matchesAccountAndNames() {
    service.isClientExist("000000001", "Ana", "", "Lopez", null, true);

    assertQuery("", "000000001", "Ana", "Lopez");
  }

  @Test
  void noMatchingClient_returnsFalse() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
        .thenReturn(0);

    assertFalse(service.isClientExist("000000001", "Ana", "Maria", "Lopez", "88887777", false));
  }

  private void assertQuery(String extraConditions, Object... expectedParams) {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
    verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), params.capture());

    assertEquals(
        "select count(*) from m_client where account_no = ? and firstname = ? and lastname = ?"
            + extraConditions,
        sql.getValue());
    assertArrayEquals(expectedParams, params.getValue());
    assertEquals(sql.getValue().chars().filter(c -> c == '?').count(), params.getValue().length);
  }
}
