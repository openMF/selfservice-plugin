/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.savings.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tenant-aware implementation that validates savings account ownership through the client-mapping
 * join.
 *
 * <p>MULTI-TENANT: All tables ({@code m_selfservice_user_client_mapping}, {@code
 * m_savings_account}) are tenant-scoped. The JdbcTemplate is routed to the correct tenant schema by
 * Fineract's infrastructure.
 *
 * <p>SECURITY: Uses INNER JOIN (not LEFT JOIN) to prevent null-match bypasses.
 */
@RequiredArgsConstructor
@Slf4j
public class AppuserSavingsMapperReadServiceImpl implements AppuserSavingsMapperReadService {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public Boolean isSavingsMappedToUser(final Long savingsId, final Long appUserId) {
    if (savingsId == null || appUserId == null) {
      return false;
    }
    final Boolean result =
        jdbcTemplate.queryForObject(
            """
                SELECT CASE WHEN (COUNT(*) > 0) THEN TRUE ELSE FALSE END
                FROM m_selfservice_user_client_mapping m
                INNER JOIN m_savings_account s ON s.client_id = m.client_id
                WHERE s.id = ? AND m.appuser_id = ?
                """,
            Boolean.class,
            savingsId,
            appUserId);
    return result != null && result;
  }
}
