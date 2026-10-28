/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TotpRecoveryCodeRepository extends JpaRepository<TotpRecoveryCode, Long> {

  long countByAppUserIdAndUsedAtIsNull(Long appUserId);

  void deleteByAppUserId(Long appUserId);

  /**
   * Uses a code up. The single conditional UPDATE makes it single use: of two concurrent requests
   * with the same code exactly one gets {@code 1}.
   *
   * @return 1 when the code existed and was unused, 0 otherwise
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE TotpRecoveryCode c SET c.usedAt = :now WHERE c.appUserId = :userId AND c.codeHash ="
          + " :codeHash AND c.usedAt IS NULL")
  int consume(
      @Param("userId") Long userId,
      @Param("codeHash") String codeHash,
      @Param("now") LocalDateTime now);
}
