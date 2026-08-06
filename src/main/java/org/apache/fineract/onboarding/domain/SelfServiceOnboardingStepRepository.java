/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SelfServiceOnboardingStepRepository
    extends JpaRepository<SelfServiceOnboardingStep, Long> {

  @Query(
      "SELECT s FROM SelfServiceOnboardingStep s JOIN FETCH s.stepDef d "
          + "WHERE s.appUserId = :userId ORDER BY d.stepOrder ASC")
  List<SelfServiceOnboardingStep> findByAppUserIdOrdered(@Param("userId") Long appUserId);

  @Query(
      "SELECT s FROM SelfServiceOnboardingStep s JOIN FETCH s.stepDef d "
          + "WHERE s.appUserId = :userId AND d.stepCode = :code")
  Optional<SelfServiceOnboardingStep> findByAppUserIdAndStepCode(
      @Param("userId") Long appUserId, @Param("code") String stepCode);

  boolean existsByAppUserId(Long appUserId);
}