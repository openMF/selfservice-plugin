package org.apache.fineract.selfservice.account.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for updating Payment Detail records.
 * Note: The injected JdbcTemplate in Fineract is tenant-aware and routes queries 
 * to the correct tenant schema automatically.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PaymentDetailDao {

    private final JdbcTemplate jdbcTemplate;

    public int updateRoutingCode(Long paymentDetailId, String routingCode) {
        if (paymentDetailId == null || routingCode == null) {
            return 0;
        }
        String sql = "UPDATE m_payment_detail SET routing_code = ? WHERE id = ?";
        try {
            log.info("Executing update routing_code for payment_detail_id: {}", paymentDetailId);
            return jdbcTemplate.update(sql, routingCode, paymentDetailId);
        } catch (Exception e) {
            log.error("Failed to update routing_code for payment_detail_id: {}", paymentDetailId, e);
            throw new RuntimeException("Database error while updating payment detail routing code", e);
        }
    }
}