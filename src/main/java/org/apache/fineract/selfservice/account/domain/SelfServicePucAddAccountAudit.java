/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "m_puc_add_account_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelfServicePucAddAccountAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "account_type", nullable = false, length = 50)
    private String accountType;

    @Column(name = "holder_id", nullable = false, length = 50)
    private String holderId;

    @Column(name = "holder_id_type", nullable = false, length = 20)
    private String holderIdType;

    @Column(name = "holder", nullable = false, length = 200)
    private String holder;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "ip_number", length = 45)
    private String ipNumber;

    @Column(name = "operation_id", length = 64)
    private String operationId;

    @Column(name = "successful", nullable = false)
    private Boolean successful;

    @Column(name = "reject_description", length = 500)
    private String rejectDescription;

    @Column(name = "created_on_utc", nullable = false)
    private OffsetDateTime createdOnUtc;
}