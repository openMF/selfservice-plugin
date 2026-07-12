/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

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
    String sql =
        "select count(*) from m_client where account_no = ? and firstname = ? and lastname = ?";
    Object[] params = new Object[] {accountNumber, firstName, lastName};
    if (!isEmailAuthenticationMode) {
      sql = sql + " and mobile_no = ?";
      params = new Object[] {accountNumber, firstName, lastName, mobileNumber};
    }

    if (!isNullOrEmpty(middleName)) {
      sql = sql + " and middlename = ?";
      params = new Object[] {accountNumber, firstName, lastName, middleName};
    }

    if (!isNullOrEmpty(middleName) && !isEmailAuthenticationMode) {
      sql = sql + " and middlename = ? and mobile_no = ?";
      params = new Object[] {accountNumber, firstName, lastName, middleName, mobileNumber};
    }

    int count = this.jdbcTemplate.queryForObject(sql, Integer.class, params);
    return count != 0;
  }

  public static boolean isNullOrEmpty(String str) {
    return str == null || str.isEmpty();
  }
}
