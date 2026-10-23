/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.activity.data.ActivityData;
import org.apache.fineract.selfservice.activity.data.ActivityPageData;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivity;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the activity feed of one user. The user id always comes from the authenticated principal
 * (never from the request), so a user can only ever read their own history.
 */
@Service
@RequiredArgsConstructor
public class ActivityQueryService {

  static final int DEFAULT_LIMIT = 20;

  private static final Gson GSON = new Gson();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

  private final SelfServiceActivityRepository repository;
  private final ActivitySettingsProvider settingsProvider;

  /**
   * @param types the types to show; {@code null} or empty means all
   * @param offset rows to skip; {@code null} means 0, negative values are treated as 0
   * @param limit page size; {@code null} means 20, and it is capped by the tenant's {@code
   *     maxPageSize}
   */
  @Transactional(readOnly = true)
  public ActivityPageData feed(
      final Long userId,
      final Collection<ActivityType> types,
      final Integer offset,
      final Integer limit) {
    final int max = settingsProvider.current().maxPageSize();
    final int pageSize = limit == null ? Math.min(DEFAULT_LIMIT, max) : clamp(limit, 1, max);
    final long start = offset == null ? 0L : Math.max(0L, offset);
    final Collection<ActivityType> wanted =
        types == null || types.isEmpty() ? EnumSet.allOf(ActivityType.class) : types;

    final long total = repository.countFeed(userId, wanted);
    final List<ActivityData> items =
        repository.findFeed(userId, wanted, new OffsetPageRequest(start, pageSize)).stream()
            .map(ActivityQueryService::toData)
            .toList();
    return new ActivityPageData(total, items);
  }

  private static int clamp(final int value, final int min, final int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static ActivityData toData(final SelfServiceActivity activity) {
    return new ActivityData(
        activity.getId(),
        activity.getType().name(),
        activity.getOutcome().name(),
        activity.getCreatedAt().toString(),
        activity.getIpMasked(),
        parseDetails(activity.getDetails()));
  }

  private static Map<String, Object> parseDetails(final String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      final Map<String, Object> parsed = GSON.fromJson(json, MAP_TYPE);
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException e) {
      return Map.of();
    }
  }
}
