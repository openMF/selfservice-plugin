/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.registration.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges expired tokens from both the plugin-owned {@code request_audit_table} and the Fineract
 * core-owned {@code twofactor_access_token} table.
 *
 * <p><strong>Data access strategy:</strong>
 *
 * <ul>
 *   <li>{@code request_audit_table} — uses JPQL via {@link SelfServiceRegistrationRepository}
 *       because the plugin owns the {@code SelfServiceRegistration} entity.
 *   <li>{@code twofactor_access_token} — uses raw SQL via {@link JdbcTemplate} because the {@code
 *       TFAccessToken} entity is owned by the {@code fineract-security} module and declaring a
 *       duplicate mapping would cause classloader conflicts at runtime.
 * </ul>
 *
 * <p>The injected {@link JdbcTemplate} <strong>must</strong> be backed by Fineract's {@code
 * AbstractRoutingDataSource} (the routing datasource) so that {@code
 * ThreadLocalContextUtil.setTenant()} routes SQL to the correct tenant schema.
 */
@Slf4j
@RequiredArgsConstructor
public class ExpiredTokenPurgeService {

  private final SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  private final JdbcTemplate jdbcTemplate;

  /**
   * Deletes all rows from {@code request_audit_table} where {@code expires_at < cutoff}.
   *
   * <p>Uses {@link DateUtils#getLocalDateTimeOfSystem()} as the cutoff — the same clock source used
   * by {@link
   * org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration#isExpired(LocalDateTime)}
   * and throughout the registration service when creating tokens. This eliminates timezone
   * divergence between token creation and purge evaluation.
   *
   * @return number of rows deleted
   */
  @Transactional
  public int purgeExpiredSelfServiceTokens() {
    LocalDateTime cutoff = DateUtils.getLocalDateTimeOfSystem();
    int deleted = selfServiceRegistrationRepository.deleteExpiredRequests(cutoff);
    if (deleted > 0) {
      log.info("Purged {} expired self-service token(s) from request_audit_table", deleted);
    } else {
      log.debug("No expired self-service tokens to purge");
    }
    return deleted;
  }

  /**
   * Deletes all rows from {@code twofactor_access_token} where {@code valid_to < NOW()}.
   *
   * <p>Uses database-side {@code NOW()} to match Fineract core's {@code TFAccessToken.isValid()}
   * which delegates to the database server's clock via {@code
   * DateUtils.getLocalDateTimeOfTenant()}. This avoids Java-to-DB clock skew.
   *
   * @return number of rows deleted
   */
  public int purgeExpiredTwoFactorAccessTokens() {
    int deleted = jdbcTemplate.update("DELETE FROM twofactor_access_token WHERE valid_to < NOW()");
    if (deleted > 0) {
      log.info("Purged {} expired 2FA access token(s) from twofactor_access_token", deleted);
    } else {
      log.debug("No expired 2FA access tokens to purge");
    }
    return deleted;
  }
}
