/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * Audit record persisted for every SAME_BANK internal transfer executed through the Self-Service
 * channel. Stores the generated operation metadata (operationId, internalRefNumber) alongside the
 * Fineract {@code account_transfer} resource id so that the transfer can be traced end-to-end.
 */
@Entity
@Table(name = "m_selfservice_same_bank_transfer_audit")
@Getter
@Setter
@NoArgsConstructor
public class SelfServiceSameBankTransferAudit extends AbstractPersistableCustom<Long> {

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private Long toAccountId;

    @Column(name = "from_account_identifier", length = 100)
    private String fromAccountIdentifier;

    @Column(name = "to_account_identifier", length = 100)
    private String toAccountIdentifier;

    @Column(name = "transfer_amount", precision = 19, scale = 6, nullable = false)
    private BigDecimal transferAmount;

    @Column(name = "fee_amount", precision = 19, scale = 6)
    private BigDecimal feeAmount;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "operation_id", length = 64, nullable = false)
    private String operationId;

    @Column(name = "internal_ref_number", length = 64, nullable = false)
    private String internalRefNumber;

    @Column(name = "fineract_transfer_id")
    private Long fineractTransferId;

    @Column(name = "transfer_description", length = 500)
    private String transferDescription;

    @Column(name = "reference", length = 200)
    private String reference;

    @Column(name = "state_description", length = 100)
    private String stateDescription;

    @Column(name = "successful", nullable = false)
    private boolean successful;

    @Column(name = "reject_description", length = 500)
    private String rejectDescription;

    @Column(name = "registration_date", nullable = false)
    private OffsetDateTime registrationDate;

    @Column(name = "processing_date", nullable = false)
    private OffsetDateTime processingDate;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;

    /**
     * Static factory that builds a fully-populated audit instance ready for persistence.
     */
    public static SelfServiceSameBankTransferAudit instance(
            final Long clientId,
            final Long fromAccountId,
            final Long toAccountId,
            final String fromAccountIdentifier,
            final String toAccountIdentifier,
            final BigDecimal transferAmount,
            final BigDecimal feeAmount,
            final String currencyCode,
            final String operationId,
            final String internalRefNumber,
            final Long fineractTransferId,
            final String transferDescription,
            final String reference,
            final String stateDescription,
            final boolean successful,
            final String rejectDescription,
            final OffsetDateTime registrationDate,
            final OffsetDateTime processingDate) {

        SelfServiceSameBankTransferAudit audit = new SelfServiceSameBankTransferAudit();
        audit.clientId = clientId;
        audit.fromAccountId = fromAccountId;
        audit.toAccountId = toAccountId;
        audit.fromAccountIdentifier = fromAccountIdentifier;
        audit.toAccountIdentifier = toAccountIdentifier;
        audit.transferAmount = transferAmount;
        audit.feeAmount = feeAmount;
        audit.currencyCode = currencyCode;
        audit.operationId = operationId;
        audit.internalRefNumber = internalRefNumber;
        audit.fineractTransferId = fineractTransferId;
        audit.transferDescription = transferDescription;
        audit.reference = reference;
        audit.stateDescription = stateDescription;
        audit.successful = successful;
        audit.rejectDescription = rejectDescription;
        audit.registrationDate = registrationDate;
        audit.processingDate = processingDate;
        audit.createdOnUtc = OffsetDateTime.now();
        return audit;
    }
}