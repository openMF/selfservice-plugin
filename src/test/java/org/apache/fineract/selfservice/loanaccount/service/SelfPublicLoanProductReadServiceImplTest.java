/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Collections;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanProductData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfPublicLoanProductReadServiceImplTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private DatabaseSpecificSQLGenerator sqlGenerator;

  private SelfPublicLoanProductReadServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new SelfPublicLoanProductReadServiceImpl(jdbcTemplate, sqlGenerator);
  }

  @Test
  void retrieveAllActiveLoanProducts_executesQueryWithActiveFilter() {
    when(sqlGenerator.currentBusinessDate()).thenReturn("CURRENT_DATE");
    when(jdbcTemplate.query(any(String.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    service.retrieveAllActiveLoanProducts();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class));
    String executedSql = sqlCaptor.getValue();
    assertTrue(executedSql.contains("close_date IS NULL"), "Should filter by close_date IS NULL");
    assertTrue(
        executedSql.contains("close_date >= CURRENT_DATE"),
        "Should filter by close_date >= currentBusinessDate");
  }

  @Test
  void retrieveAllActiveLoanProducts_queryIncludesSimulationFields() {
    when(sqlGenerator.currentBusinessDate()).thenReturn("CURRENT_DATE");
    when(jdbcTemplate.query(any(String.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    service.retrieveAllActiveLoanProducts();

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class));
    String executedSql = sqlCaptor.getValue();
    assertTrue(executedSql.contains("principal_amount"), "Should select principal");
    assertTrue(
        executedSql.contains("nominal_interest_rate_per_period"), "Should select interest rate");
    assertTrue(executedSql.contains("number_of_repayments"), "Should select repayments");
    assertTrue(executedSql.contains("currency_code"), "Should select currency");
    assertTrue(executedSql.contains("m_currency"), "Should join currency table");
  }

  @Test
  void retrieveAllActiveLoanProducts_emptyResult_returnsEmptyCollection() {
    when(sqlGenerator.currentBusinessDate()).thenReturn("CURRENT_DATE");
    when(jdbcTemplate.query(any(String.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    Collection<SelfPublicLoanProductData> result = service.retrieveAllActiveLoanProducts();

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
