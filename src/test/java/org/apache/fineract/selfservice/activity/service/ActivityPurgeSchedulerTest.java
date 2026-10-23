/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityPurgeSchedulerTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 3, 45);

  @Mock private SelfServiceActivityRepository repository;
  @Mock private ActivitySettingsProvider settingsProvider;
  @Mock private TenantDetailsService tenantDetailsService;
  @Mock private FineractProperties fineractProperties;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private FineractProperties.FineractModeProperties mode;

  private ActivityPurgeScheduler scheduler;

  @BeforeEach
  void setUp() {
    when(fineractProperties.getMode()).thenReturn(mode);
    when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(NOW);
    scheduler =
        new ActivityPurgeScheduler(
            repository,
            settingsProvider,
            tenantDetailsService,
            fineractProperties,
            new TransactionTemplate(transactionManager),
            transactionDateUtil);
  }

  @Test
  void deletesWhatIsOlderThanTheTenantsRetention() {
    when(settingsProvider.current())
        .thenReturn(ActivitySettings.fromProperties(Map.of("retentionDays", "365")));
    when(repository.deleteCreatedBefore(any())).thenReturn(12);

    assertEquals(12, scheduler.purgeCurrentTenant());

    verify(repository).deleteCreatedBefore(NOW.minusDays(365));
  }

  @Test
  void theDefaultRetentionIsNinetyDays() {
    when(settingsProvider.current()).thenReturn(ActivitySettings.defaults());

    scheduler.purgeCurrentTenant();

    verify(repository).deleteCreatedBefore(NOW.minusDays(90));
  }

  @Test
  void anInstanceThatIsNotABatchInstance_doesNothing() {
    when(mode.isBatchWorkerEnabled()).thenReturn(false);
    when(mode.isBatchManagerEnabled()).thenReturn(false);

    assertFalse(scheduler.isBatchInstance());
    scheduler.purgeOldActivity();

    verify(tenantDetailsService, never()).findAllTenants();
    verify(repository, never()).deleteCreatedBefore(any());
  }

  @Test
  void aFailureInOneTenant_doesNotStopTheOthers() {
    when(mode.isBatchWorkerEnabled()).thenReturn(true);
    final FineractPlatformTenant first = mock(FineractPlatformTenant.class);
    final FineractPlatformTenant second = mock(FineractPlatformTenant.class);
    when(tenantDetailsService.findAllTenants()).thenReturn(List.of(first, second));
    when(settingsProvider.current())
        .thenThrow(new IllegalStateException("tenant database down"))
        .thenReturn(ActivitySettings.defaults());

    scheduler.purgeOldActivity();

    verify(repository).deleteCreatedBefore(NOW.minusDays(90));
  }
}
