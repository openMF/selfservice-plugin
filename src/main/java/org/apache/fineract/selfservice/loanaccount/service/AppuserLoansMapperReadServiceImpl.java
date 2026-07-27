/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tenant-aware implementation that validates loan account ownership through the client-mapping
 * join.
 *
 * <p>MULTI-TENANT: All tables are tenant-scoped. JdbcTemplate is routed to the correct tenant
 * schema.
 *
 * <p>SECURITY: Uses INNER JOIN (not LEFT JOIN) to prevent null-match bypasses.
 */
@RequiredArgsConstructor
@Slf4j
public class AppuserLoansMapperReadServiceImpl implements AppuserLoansMapperReadService {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public Boolean isLoanMappedToUser(final Long loanId, final Long appUserId) {
    if (loanId == null || appUserId == null) {
      return false;
    }
    final Boolean result =
        jdbcTemplate.queryForObject(
            """
                SELECT CASE WHEN (COUNT(*) > 0) THEN TRUE ELSE FALSE END
                FROM m_selfservice_user_client_mapping m
                INNER JOIN m_loan l ON l.client_id = m.client_id
                WHERE l.id = ? AND m.appuser_id = ?
                """,
            Boolean.class,
            loanId,
            appUserId);
    return result != null && result;
  }
}
