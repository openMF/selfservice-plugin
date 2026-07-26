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