/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
public class SelfServiceRegistrationReadPlatformServiceImpl
    implements SelfServiceRegistrationReadPlatformService {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public boolean isClientExist(
      String accountNumber,
      String firstName,
      String middleName,
      String lastName,
      String mobileNumber,
      boolean isEmailAuthenticationMode) {
    // Each condition adds its placeholder and its value together, so the two cannot get out of step
    final StringBuilder sql =
        new StringBuilder(
            "select count(*) from m_client"
                + " where account_no = ? and firstname = ? and lastname = ?");
    final List<Object> params = new ArrayList<>();
    params.add(accountNumber);
    params.add(firstName);
    params.add(lastName);
    if (!isNullOrEmpty(middleName)) {
      sql.append(" and middlename = ?");
      params.add(middleName);
    }
    if (!isEmailAuthenticationMode) {
      sql.append(" and mobile_no = ?");
      params.add(mobileNumber);
    }

    Integer count =
        this.jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
    return count != null && count != 0;
  }

  public static boolean isNullOrEmpty(String str) {
    return str == null || str.isEmpty();
  }
}
