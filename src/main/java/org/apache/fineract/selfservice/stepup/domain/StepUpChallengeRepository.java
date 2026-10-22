/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StepUpChallengeRepository extends JpaRepository<StepUpChallenge, Long> {

  /**
   * Loads a challenge only if it belongs to the given user AND session, taking a pessimistic write
   * lock so concurrent guesses/resends on the same challenge are serialised (attempt counting must
   * not be raced).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT c FROM StepUpChallenge c WHERE c.id = :id AND c.appUserId = :userId AND"
          + " c.sessionHash = :sessionHash")
  Optional<StepUpChallenge> findOwnedForUpdate(
      @Param("id") Long id, @Param("userId") Long userId, @Param("sessionHash") String sessionHash);

  /** Open (not yet terminal) challenges of a user for one action, used to supersede them. */
  @Query(
      "SELECT c FROM StepUpChallenge c WHERE c.appUserId = :userId AND c.action = :action AND"
          + " c.status IN :statuses")
  List<StepUpChallenge> findByUserActionAndStatusIn(
      @Param("userId") Long userId,
      @Param("action") StepUpAction action,
      @Param("statuses") List<StepUpStatus> statuses);

  /** Most recent lockout still in force for this user and action, if any. */
  @Query(
      "SELECT c FROM StepUpChallenge c WHERE c.appUserId = :userId AND c.action = :action AND"
          + " c.status = :locked AND c.lockedUntil > :now ORDER BY c.lockedUntil DESC")
  List<StepUpChallenge> findActiveLockouts(
      @Param("userId") Long userId,
      @Param("action") StepUpAction action,
      @Param("locked") StepUpStatus locked,
      @Param("now") LocalDateTime now);

  /** Number of challenges created by a user since {@code since} (creation rate limit). */
  @Query(
      "SELECT COUNT(c) FROM StepUpChallenge c WHERE c.appUserId = :userId AND c.createdAt >"
          + " :since")
  long countCreatedSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

  Optional<StepUpChallenge> findByGrantHash(String grantHash);

  /**
   * Atomically consumes a grant. The single conditional UPDATE is what makes the grant one-time: of
   * two concurrent requests presenting the same token exactly one gets {@code 1}, the other {@code
   * 0}. Every binding (user, session, action, payload) and the grant expiry are part of the WHERE
   * clause, so a mismatch is indistinguishable from an unknown token.
   *
   * @return number of rows consumed (1 on success, 0 otherwise)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE StepUpChallenge c SET c.status = :consumed, c.consumedAt = :now, c.version ="
          + " c.version + 1 WHERE c.grantHash = :grantHash AND c.status = :verified AND"
          + " c.appUserId = :userId AND c.sessionHash = :sessionHash AND c.action = :action AND"
          + " c.payloadHash = :payloadHash AND c.grantExpiresAt > :now")
  int consumeGrant(
      @Param("grantHash") String grantHash,
      @Param("userId") Long userId,
      @Param("sessionHash") String sessionHash,
      @Param("action") StepUpAction action,
      @Param("payloadHash") String payloadHash,
      @Param("verified") StepUpStatus verified,
      @Param("consumed") StepUpStatus consumed,
      @Param("now") LocalDateTime now);

  /** Retention purge: challenges double as an audit trail, so they are kept for a while. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM StepUpChallenge c WHERE c.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
