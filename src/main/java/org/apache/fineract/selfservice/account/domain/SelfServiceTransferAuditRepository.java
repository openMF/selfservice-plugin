/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface SelfServiceTransferAuditRepository extends JpaRepository<SelfServiceTransferAudit, Long> {
    
    @Query("SELECT COALESCE(SUM(t.transferAmount), 0) FROM SelfServiceTransferAudit t " +
           "WHERE t.clientId = :clientId " +
           "AND t.transferType = 'SINPE_MOVIL' " +
           "AND t.currencyCode = 'CRC' " +
           "AND t.status != 'FAILED' " +
           "AND FUNCTION('DATE', t.processingDate) = :date")
    BigDecimal getDailySinpeMovilTotal(@Param("clientId") Long clientId, @Param("date") LocalDate date);
}