/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SelfServiceActivityRepository extends JpaRepository<SelfServiceActivity, Long> {

  /** Newest first. {@code offset}/{@code limit} are applied by the caller through a Pageable. */
  @Query(
      "SELECT a FROM SelfServiceActivity a WHERE a.appUserId = :userId AND a.type IN :types"
          + " ORDER BY a.createdAt DESC, a.id DESC")
  List<SelfServiceActivity> findFeed(
      @Param("userId") Long userId,
      @Param("types") Collection<ActivityType> types,
      org.springframework.data.domain.Pageable pageable);

  @Query(
      "SELECT COUNT(a) FROM SelfServiceActivity a WHERE a.appUserId = :userId AND a.type IN"
          + " :types")
  long countFeed(@Param("userId") Long userId, @Param("types") Collection<ActivityType> types);

  /** Used to throttle noisy event types (one failed login per minute, see ActivityLogService). */
  @Query(
      "SELECT COUNT(a) FROM SelfServiceActivity a WHERE a.appUserId = :userId AND a.type = :type"
          + " AND a.createdAt >= :since")
  long countSince(
      @Param("userId") Long userId,
      @Param("type") ActivityType type,
      @Param("since") LocalDateTime since);

  /** Retention purge. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM SelfServiceActivity a WHERE a.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
