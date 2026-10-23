/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.activity.domain.ActivityOutcome;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivity;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityLogServiceTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 9, 30, 0);

  @Mock private SelfServiceActivityRepository repository;
  @Mock private ActivitySettingsProvider settingsProvider;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private PlatformTransactionManager transactionManager;

  private ActivityLogService service;
  private String address = "203.0.113.9";

  @BeforeEach
  void setUp() {
    when(settingsProvider.current()).thenReturn(ActivitySettings.defaults());
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(NOW);
    service =
        new ActivityLogService(
            repository,
            settingsProvider,
            transactionDateUtil,
            new TransactionTemplate(transactionManager),
            () -> address);
  }

  private SelfServiceActivity saved() {
    final ArgumentCaptor<SelfServiceActivity> captor =
        ArgumentCaptor.forClass(SelfServiceActivity.class);
    verify(repository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void savesTheActivityWithTheMaskedIpAndTheTenantClock() {
    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, 3L, Map.of());

    final SelfServiceActivity row = saved();
    assertEquals(7L, row.getAppUserId());
    assertEquals(3L, row.getClientId());
    assertEquals(ActivityType.LOGIN, row.getType());
    assertEquals(ActivityOutcome.SUCCESS, row.getOutcome());
    assertEquals("203.0.x.x", row.getIpMasked());
    assertEquals(NOW, row.getCreatedAt());
    assertNull(row.getDetails());
  }

  @Test
  void theFullIpAddressIsNeverStored() {
    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null);

    assertEquals("203.0.x.x", saved().getIpMasked());
  }

  @Test
  void onlyWhitelistedDetailsAreStored() {
    final Map<String, Object> source = new HashMap<>();
    source.put("transactionAmount", "10.00");
    source.put("currencyCode", "USD");
    source.put("email", "ana@example.org");
    source.put("authCode", "123456");

    service.record(ActivityType.TRANSFER_CONFIRMED, ActivityOutcome.SUCCESS, 7L, null, source);

    final String details = saved().getDetails();
    assertEquals("{\"amount\":\"10.00\",\"currency\":\"USD\"}", details);
  }

  @Test
  void aTenantThatSwitchedTheLogOff_getsNothingWritten() {
    when(settingsProvider.current())
        .thenReturn(ActivitySettings.fromProperties(Map.of("enabled", "false")));

    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null);

    verifyNoInteractions(repository);
  }

  @Test
  void missingUserTypeOrOutcome_isIgnored() {
    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, null, null, null);
    service.record(null, ActivityOutcome.SUCCESS, 7L, null, null);
    service.record(ActivityType.LOGIN, null, 7L, null, null);

    verifyNoInteractions(repository);
  }

  @Test
  void failedLogins_areLimitedToOnePerMinutePerUser() {
    when(repository.countSince(eq(7L), eq(ActivityType.LOGIN_FAILED), any())).thenReturn(1L);

    service.record(ActivityType.LOGIN_FAILED, ActivityOutcome.FAILURE, 7L, null, null);

    verify(repository, never()).save(any());
    verify(repository)
        .countSince(
            7L, ActivityType.LOGIN_FAILED, NOW.minus(ActivityLogService.FAILED_LOGIN_THROTTLE));
  }

  @Test
  void aFirstFailedLogin_isRecorded() {
    when(repository.countSince(eq(7L), eq(ActivityType.LOGIN_FAILED), any())).thenReturn(0L);

    service.record(ActivityType.LOGIN_FAILED, ActivityOutcome.FAILURE, 7L, null, null);

    assertEquals(ActivityOutcome.FAILURE, saved().getOutcome());
  }

  @Test
  void otherTypesAreNotThrottled() {
    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null);

    verify(repository, never()).countSince(any(), any(), any());
  }

  @Test
  void aFailureToWrite_neverReachesTheCaller() {
    when(repository.save(any())).thenThrow(new IllegalStateException("database down"));

    assertDoesNotThrow(
        () -> service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null));
  }

  @Test
  void aFailureToReadTheSettingsOrTheClock_neverReachesTheCaller() {
    when(transactionDateUtil.getCurrentTenantLocalDateTime())
        .thenThrow(new IllegalStateException("no tenant"));

    assertDoesNotThrow(
        () -> service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null));
    verify(repository, never()).save(any());
  }

  @Test
  void theRowIsWrittenInItsOwnTransaction() {
    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null);

    final ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertEquals(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        definition.getValue().getPropagationBehavior());
  }

  @Test
  void noAddressMeansNoIp() {
    address = null;

    service.record(ActivityType.LOGIN, ActivityOutcome.SUCCESS, 7L, null, null);

    assertNull(saved().getIpMasked());
  }

  @Test
  void theAddressIsTheOneOfTheConnection_aForgedForwardedForHeaderIsIgnored() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");
    request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
    request.addHeader("X-Real-Ip", "203.0.113.10");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      assertEquals("198.51.100.7", ActivityLogService.connectionAddress());
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  void outsideARequestThereIsNoAddress() {
    RequestContextHolder.resetRequestAttributes();

    assertNull(ActivityLogService.connectionAddress());
  }
}
