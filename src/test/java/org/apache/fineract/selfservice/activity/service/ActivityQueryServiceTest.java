/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.apache.fineract.selfservice.activity.data.ActivityData;
import org.apache.fineract.selfservice.activity.data.ActivityPageData;
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
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityQueryServiceTest {

  @Mock private SelfServiceActivityRepository repository;
  @Mock private ActivitySettingsProvider settingsProvider;

  private ActivityQueryService service;

  @BeforeEach
  void setUp() {
    when(settingsProvider.current()).thenReturn(ActivitySettings.defaults());
    when(repository.findFeed(any(), any(), any())).thenReturn(List.of());
    service = new ActivityQueryService(repository, settingsProvider);
  }

  private Pageable pageUsed() {
    final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(repository).findFeed(eq(7L), any(), captor.capture());
    return captor.getValue();
  }

  @Test
  void defaults_toTheFirstTwentyOfAllTypes() {
    service.feed(7L, null, null, null);

    final Pageable page = pageUsed();
    assertEquals(0L, page.getOffset());
    assertEquals(20, page.getPageSize());
    final ArgumentCaptor<Collection<ActivityType>> types =
        ArgumentCaptor.forClass(Collection.class);
    verify(repository).countFeed(eq(7L), types.capture());
    assertEquals(EnumSet.allOf(ActivityType.class), EnumSet.copyOf(types.getValue()));
  }

  @Test
  void aPageSizeAboveTheTenantMaximumIsCapped() {
    when(settingsProvider.current())
        .thenReturn(ActivitySettings.fromProperties(Map.of("maxPageSize", "50")));

    service.feed(7L, null, 0, 9999);

    assertEquals(50, pageUsed().getPageSize());
  }

  @Test
  void theDefaultPageSizeNeverExceedsASmallTenantMaximum() {
    when(settingsProvider.current())
        .thenReturn(ActivitySettings.fromProperties(Map.of("maxPageSize", "10")));

    service.feed(7L, null, null, null);

    assertEquals(10, pageUsed().getPageSize());
  }

  @Test
  void aNegativeOffsetCountsAsZero_andAnOffsetIsPassedOn() {
    service.feed(7L, null, -4, 10);
    assertEquals(0L, pageUsed().getOffset());
  }

  @Test
  void theOffsetIsNotRoundedToWholePages() {
    service.feed(7L, null, 25, 10);
    assertEquals(25L, pageUsed().getOffset());
  }

  @Test
  void aLimitBelowOneBecomesOne() {
    service.feed(7L, null, 0, 0);
    assertEquals(1, pageUsed().getPageSize());
  }

  @Test
  void theTypeFilterIsPassedToTheQuery() {
    service.feed(7L, EnumSet.of(ActivityType.LOGIN, ActivityType.TRANSFER_CONFIRMED), 0, 10);

    final ArgumentCaptor<Collection<ActivityType>> types =
        ArgumentCaptor.forClass(Collection.class);
    verify(repository).countFeed(eq(7L), types.capture());
    assertEquals(
        EnumSet.of(ActivityType.LOGIN, ActivityType.TRANSFER_CONFIRMED),
        EnumSet.copyOf(types.getValue()));
  }

  @Test
  void onlyTheGivenUsersRowsAreEverRequested() {
    service.feed(7L, null, 0, 10);

    verify(repository).countFeed(eq(7L), any());
    verify(repository).findFeed(eq(7L), any(), any());
  }

  @Test
  void rowsAreMappedToTheApiShape_withParsedDetails() {
    final SelfServiceActivity row =
        new SelfServiceActivity(
            7L,
            null,
            ActivityType.TRANSFER_CONFIRMED,
            ActivityOutcome.SUCCESS,
            "203.0.x.x",
            "{\"amount\":\"10.00\",\"currency\":\"USD\"}",
            LocalDateTime.of(2026, 10, 23, 9, 30, 5));
    when(repository.findFeed(any(), any(), any())).thenReturn(List.of(row));
    when(repository.countFeed(any(), any())).thenReturn(41L);

    final ActivityPageData page = service.feed(7L, null, 0, 10);

    assertEquals(41L, page.totalFilteredRecords());
    final ActivityData item = page.pageItems().get(0);
    assertEquals("TRANSFER_CONFIRMED", item.type());
    assertEquals("SUCCESS", item.outcome());
    assertEquals("2026-10-23T09:30:05", item.occurredAt());
    assertEquals("203.0.x.x", item.ipAddress());
    assertEquals("10.00", item.details().get("amount"));
    assertEquals("USD", item.details().get("currency"));
  }

  @Test
  void aRowWithoutOrWithBrokenDetails_hasEmptyDetails() {
    final SelfServiceActivity none =
        new SelfServiceActivity(
            7L, null, ActivityType.LOGIN, ActivityOutcome.SUCCESS, null, null, LocalDateTime.now());
    final SelfServiceActivity broken =
        new SelfServiceActivity(
            7L,
            null,
            ActivityType.LOGIN,
            ActivityOutcome.SUCCESS,
            null,
            "{not json",
            LocalDateTime.now());
    when(repository.findFeed(any(), any(), any())).thenReturn(List.of(none, broken));

    final ActivityPageData page = service.feed(7L, null, 0, 10);

    assertTrue(page.pageItems().get(0).details().isEmpty());
    assertTrue(page.pageItems().get(1).details().isEmpty());
    assertNull(page.pageItems().get(0).ipAddress());
  }
}
