/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link SelfServiceSameBankTransferAudit}. Multi-tenancy is handled
 * transparently by Fineract's {@code TenantDataSource} routing — every query is automatically
 * scoped to the current tenant schema.
 */
@Repository
public interface SelfServiceSameBankTransferAuditRepository
        extends JpaRepository<SelfServiceSameBankTransferAudit, Long> {

  /** Retrieves an audit record by the generated operation UUID. */
  Optional<SelfServiceSameBankTransferAudit> findByOperationId(String operationId);

  /** Retrieves an audit record by the platform-generated internal reference number. */
  Optional<SelfServiceSameBankTransferAudit> findByInternalRefNumber(String internalRefNumber);

  /** Lists all transfers for a given client, most recent first. */
  List<SelfServiceSameBankTransferAudit> findByClientIdOrderByCreatedOnUtcDesc(Long clientId);

  /** Lists all transfers for a given client within a date range. */
  @Query(
          "SELECT a FROM SelfServiceSameBankTransferAudit a "
                  + "WHERE a.clientId = :clientId "
                  + "AND a.createdOnUtc BETWEEN :from AND :to "
                  + "ORDER BY a.createdOnUtc DESC")
  List<SelfServiceSameBankTransferAudit> findByClientIdAndDateRange(
          @Param("clientId") Long clientId,
          @Param("from") LocalDateTime from,
          @Param("to") LocalDateTime to);

  /** Looks up an audit record by the Fineract account-transfer resource id. */
  Optional<SelfServiceSameBankTransferAudit> findByFineractTransferId(Long fineractTransferId);

  /** Counts successful transfers for a client on a given day (useful for daily-limit checks). */
  @Query(
          "SELECT COUNT(a) FROM SelfServiceSameBankTransferAudit a "
                  + "WHERE a.clientId = :clientId "
                  + "AND a.successful = true "
                  + "AND CAST(a.createdOnUtc AS DATE) = CAST(:date AS DATE)")
  long countSuccessfulByClientIdAndDate(
          @Param("clientId") Long clientId, @Param("date") LocalDateTime date);

  /**
   * Sums the transfer amounts for a client on a given day (useful for daily-amount-limit checks).
   */
  @Query(
          "SELECT COALESCE(SUM(a.transferAmount), 0) FROM SelfServiceSameBankTransferAudit a "
                  + "WHERE a.clientId = :clientId "
                  + "AND a.successful = true "
                  + "AND CAST(a.createdOnUtc AS DATE) = CAST(:date AS DATE)")
  BigDecimal sumSuccessfulAmountByClientIdAndDate(
          @Param("clientId") Long clientId, @Param("date") LocalDateTime date);

  /**
   * Retrieves an audit detail validating ownership for a given client and account.
   * Matches against operationId, internalRefNumber, or fineractTransferId.
   */
  @Query(
          "SELECT a FROM SelfServiceSameBankTransferAudit a "
                  + "WHERE a.clientId = :clientId "
                  + "AND (a.fromAccountId = :accountId OR a.toAccountId = :accountId) "
                  + "AND (a.operationId = :txnId OR a.internalRefNumber = :txnId OR CAST(a.fineractTransferId AS string) = :txnId)")
  Optional<SelfServiceSameBankTransferAudit> findAuditDetail(
          @Param("clientId") Long clientId,
          @Param("accountId") Long accountId,
          @Param("txnId") String txnId);
}