/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Transfers held for a second signature (MX-489). */
public interface SelfServiceTransferApprovalRepository
    extends JpaRepository<SelfServiceTransferApproval, Long> {

  /** Approvals in {@code status} for any of {@code clientIds}, oldest first. */
  List<SelfServiceTransferApproval> findByClientIdInAndStatusOrderByIdAsc(
      Collection<Long> clientIds, SelfServiceTransferApproval.Status status);

  /**
   * Moves an approval from {@code expected} to {@code decision}. Returns 0 when another request
   * decided it first: the row lock makes the second concurrent UPDATE re-read the status and match
   * nothing.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE SelfServiceTransferApproval a SET a.status = :decision, a.decidedBy = :decidedBy,"
          + " a.decidedAt = :decidedAt WHERE a.id = :id AND a.status = :expected")
  int decide(
      @Param("id") Long id,
      @Param("expected") SelfServiceTransferApproval.Status expected,
      @Param("decision") SelfServiceTransferApproval.Status decision,
      @Param("decidedBy") Long decidedBy,
      @Param("decidedAt") LocalDateTime decidedAt);
}
