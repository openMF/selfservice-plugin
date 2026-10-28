/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TotpEnrollmentRepository extends JpaRepository<TotpEnrollment, Long> {

  Optional<TotpEnrollment> findByAppUserId(Long appUserId);

  /**
   * The enrolment of a user under a pessimistic write lock: two requests with the same code must
   * not both pass the replay check, and failures must not be lost to a race.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM TotpEnrollment e WHERE e.appUserId = :userId")
  Optional<TotpEnrollment> findByAppUserIdForUpdate(@Param("userId") Long userId);

  void deleteByAppUserId(Long appUserId);
}
