package org.apache.fineract.selfservice.account.domain;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "m_selfservice_transfer_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelfServiceTransferAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "transfer_type", nullable = false, length = 50)
    private String transferType;

    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode;

    @Column(name = "transfer_amount", nullable = false, precision = 19, scale = 6)
    private BigDecimal transferAmount;

    @Column(name = "fee_amount", precision = 19, scale = 6)
    private BigDecimal feeAmount;

    @Column(name = "processing_date", nullable = false)
    private OffsetDateTime processingDate;

    @Column(name = "status", length = 100)
    private String status;
}