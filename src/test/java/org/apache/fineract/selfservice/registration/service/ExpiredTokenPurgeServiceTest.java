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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ExpiredTokenPurgeServiceTest {

  @Mock private SelfServiceRegistrationRepository selfServiceRegistrationRepository;
  @Mock private JdbcTemplate jdbcTemplate;
  @Captor private ArgumentCaptor<LocalDateTime> cutoffCaptor;
  @Captor private ArgumentCaptor<String> sqlCaptor;

  private ExpiredTokenPurgeService service;

  @BeforeEach
  void setUp() {
    service = new ExpiredTokenPurgeService(selfServiceRegistrationRepository, jdbcTemplate);
  }

  // ── Self-service token purge ──────────────────────────────────────────────

  @Test
  void purgeExpiredSelfServiceTokens_deletesExpiredRows() {
    when(selfServiceRegistrationRepository.deleteExpiredRequests(any(LocalDateTime.class)))
        .thenReturn(5);

    int deleted = service.purgeExpiredSelfServiceTokens();

    assertEquals(5, deleted);
    verify(selfServiceRegistrationRepository).deleteExpiredRequests(cutoffCaptor.capture());

    // The cutoff must be close to "now" — allow 5 seconds tolerance
    LocalDateTime capturedCutoff = cutoffCaptor.getValue();
    LocalDateTime now = LocalDateTime.now();
    assertEquals(
        true,
        capturedCutoff.isBefore(now.plusSeconds(1)) && capturedCutoff.isAfter(now.minusSeconds(5)),
        "Cutoff should be approximately 'now', got: " + capturedCutoff);
  }

  @Test
  void purgeExpiredSelfServiceTokens_returnsZeroWhenNothingExpired() {
    when(selfServiceRegistrationRepository.deleteExpiredRequests(any(LocalDateTime.class)))
        .thenReturn(0);

    int deleted = service.purgeExpiredSelfServiceTokens();

    assertEquals(0, deleted);
    verify(selfServiceRegistrationRepository).deleteExpiredRequests(any(LocalDateTime.class));
  }

  @Test
  void purgeExpiredSelfServiceTokens_passesNonNullCutoff() {
    when(selfServiceRegistrationRepository.deleteExpiredRequests(any(LocalDateTime.class)))
        .thenReturn(0);

    service.purgeExpiredSelfServiceTokens();

    verify(selfServiceRegistrationRepository).deleteExpiredRequests(cutoffCaptor.capture());
    LocalDateTime cutoff = cutoffCaptor.getValue();
    assertEquals(true, cutoff != null, "Cutoff must not be null");
  }

  @Test
  void purgeExpiredSelfServiceTokens_doesNotTouch2faTable() {
    when(selfServiceRegistrationRepository.deleteExpiredRequests(any(LocalDateTime.class)))
        .thenReturn(3);

    service.purgeExpiredSelfServiceTokens();

    verify(jdbcTemplate, never()).update(any(String.class));
  }

  // ── 2FA token purge ───────────────────────────────────────────────────────

  @Test
  void purgeExpiredTwoFactorAccessTokens_executesCorrectSql() {
    when(jdbcTemplate.update(any(String.class))).thenReturn(10);

    int deleted = service.purgeExpiredTwoFactorAccessTokens();

    assertEquals(10, deleted);
    verify(jdbcTemplate).update(sqlCaptor.capture());
    assertEquals("DELETE FROM twofactor_access_token WHERE valid_to < NOW()", sqlCaptor.getValue());
  }

  @Test
  void purgeExpiredTwoFactorAccessTokens_returnsZeroWhenNothingExpired() {
    when(jdbcTemplate.update(any(String.class))).thenReturn(0);

    int deleted = service.purgeExpiredTwoFactorAccessTokens();

    assertEquals(0, deleted);
  }

  @Test
  void purgeExpiredTwoFactorAccessTokens_propagatesDeleteCount() {
    when(jdbcTemplate.update(any(String.class))).thenReturn(42);

    int deleted = service.purgeExpiredTwoFactorAccessTokens();

    assertEquals(42, deleted);
  }

  @Test
  void purgeExpiredTwoFactorAccessTokens_doesNotTouchSelfServiceTable() {
    when(jdbcTemplate.update(any(String.class))).thenReturn(1);

    service.purgeExpiredTwoFactorAccessTokens();

    verify(selfServiceRegistrationRepository, never())
        .deleteExpiredRequests(any(LocalDateTime.class));
  }

  // ── Independence: calling one method does not invoke the other ────────────

  @Test
  void methodsAreIndependent_selfServiceDoesNotTrigger2fa() {
    when(selfServiceRegistrationRepository.deleteExpiredRequests(any(LocalDateTime.class)))
        .thenReturn(1);

    service.purgeExpiredSelfServiceTokens();

    verify(jdbcTemplate, never()).update(any(String.class));
    verify(selfServiceRegistrationRepository).deleteExpiredRequests(any(LocalDateTime.class));
  }

  @Test
  void methodsAreIndependent_2faDoesNotTriggerSelfService() {
    when(jdbcTemplate.update(any(String.class))).thenReturn(1);

    service.purgeExpiredTwoFactorAccessTokens();

    verify(selfServiceRegistrationRepository, never())
        .deleteExpiredRequests(any(LocalDateTime.class));
    verify(jdbcTemplate).update(any(String.class));
  }
}
