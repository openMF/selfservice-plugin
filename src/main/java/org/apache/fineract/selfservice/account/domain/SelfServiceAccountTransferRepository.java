package org.apache.fineract.selfservice.account.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SelfServiceAccountTransferRepository extends JpaRepository<SelfServiceSameBankTransferAudit, Long> {

    /**
     * Retrieves created_on_utc as Instant for a given savings transaction.
     */
    @Query(value = "SELECT created_on_utc FROM m_savings_account_transaction WHERE id = :transferId", nativeQuery = true)
    Instant getCreatedOnUtcByTransferId(@Param("transferId") Long transferId);
}