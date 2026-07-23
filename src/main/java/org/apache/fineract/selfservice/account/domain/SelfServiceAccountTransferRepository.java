package org.apache.fineract.selfservice.account.domain;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Custom repository for handling Self-Service specific queries against
 * the Fineract m_savings_account_transaction table.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class SelfServiceAccountTransferRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Retrieves the created_on_utc timestamp for a specific savings account transaction.
     *
     * @param transferId The ID of the transfer (savings account transaction).
     * @return The creation time as an Instant, or null if the transaction is not found or an error occurs.
     */
    public Instant findCreatedOnUtcByTransferId(Long transferId) {
        if (transferId == null) {
            return null;
        }

        String sql = "SELECT created_on_utc FROM m_savings_account_transaction WHERE id = ?";

        try {
            Timestamp ts = jdbcTemplate.queryForObject(sql, Timestamp.class, transferId);
            if (ts != null) {
                // Convert to system default timezone
                return ts.toInstant();
            }
        } catch (Exception e) {
            log.warn("Could not fetch created_on_utc for transfer id: {}", transferId, e);
        }

        return null;
    }
}