/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.account.data;

import java.util.Collection;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;

public class SelfBeneficiariesTPTData {

    private final Long id;
    private final String name;
    private final String officeName;
    private final String clientName;
    private final EnumOptionData accountType;
    private final String accountNumber;
    private final String iban;
    private final Long transferLimit;
    private final Collection<EnumOptionData> accountTypeOptions;

    private final String customAccountNumber;
    private final String holderName;
    private final String holderId;
    private final Integer holderIdType;
    private final String currencyCode;
    private final String entityCode;
    private final String entityName;

    // New fields for explicit payment routing and currency handling
    private final String paymentType;
    private final String currency;

    /**
     * Constructor for template response (returns available account type options).
     */
    public SelfBeneficiariesTPTData(final Collection<EnumOptionData> accountTypeOptions) {
        this.accountTypeOptions = accountTypeOptions;
        this.id = null;
        this.name = null;
        this.officeName = null;
        this.clientName = null;
        this.accountType = null;
        this.accountNumber = null;
        this.iban = null;
        this.transferLimit = null;
        this.customAccountNumber = null;
        this.holderName = null;
        this.holderId = null;
        this.holderIdType = null;
        this.currencyCode = null;
        this.entityCode = null;
        this.entityName = null;
        this.paymentType = null;
        this.currency = null;
    }

    /**
     * 🟢 Constructor for basic list response (internal accounts with IBAN added).
     */
    public SelfBeneficiariesTPTData(
            final Long id,
            final String name,
            final String officeName,
            final String clientName,
            final EnumOptionData accountType,
            final String accountNumber,
            final String iban,
            final Long transferLimit) {
        this.accountTypeOptions = null;
        this.id = id;
        this.name = name;
        this.officeName = officeName;
        this.clientName = clientName;
        this.accountType = accountType;
        this.accountNumber = accountNumber;
        this.iban = iban;
        this.transferLimit = transferLimit;
        this.customAccountNumber = null;
        this.holderName = null;
        this.holderId = null;
        this.holderIdType = null;
        this.currencyCode = null;
        this.entityCode = null;
        this.entityName = null;
        this.paymentType = null;
        this.currency = null;
    }

    public SelfBeneficiariesTPTData(
            final Long id,
            final String name,
            final String officeName,
            final String clientName,
            final EnumOptionData accountType,
            final String accountNumber,
            final String iban,
            final Long transferLimit,
            final String customAccountNumber,
            final String holderName,
            final String holderId,
            final Integer holderIdType,
            final String currencyCode,
            final String entityCode,
            final String entityName,
            final String paymentType,
            final String currency) {
        this.accountTypeOptions = null;
        this.id = id;
        this.name = name;
        this.officeName = officeName;
        this.clientName = clientName;
        this.accountType = accountType;
        this.accountNumber = accountNumber;
        this.iban = customAccountNumber;
        this.transferLimit = transferLimit;
        this.customAccountNumber = customAccountNumber;
        this.holderName = holderName;
        this.holderId = holderId;
        this.holderIdType = holderIdType;
        this.currencyCode = currencyCode;
        this.entityCode = entityCode;
        this.entityName = entityName;
        this.paymentType = paymentType;
        this.currency = currency;
    }

    // --- Getters ---

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getOfficeName() { return officeName; }
    public String getClientName() { return clientName; }
    public EnumOptionData getAccountType() { return accountType; }
    public String getAccountNumber() { return accountNumber; }
    public String getIban() { return iban; }
    public Long getTransferLimit() { return transferLimit; }
    public Collection<EnumOptionData> getAccountTypeOptions() { return accountTypeOptions; }

    public String getCustomAccountNumber() { return customAccountNumber; }
    public String getHolderName() { return holderName; }
    public String getHolderId() { return holderId; }
    public Integer getHolderIdType() { return holderIdType; }
    public String getCurrencyCode() { return currencyCode; }
    public String getEntityCode() { return entityCode; }
    public String getEntityName() { return entityName; }

    public String getPaymentType() { return paymentType; }
    public String getCurrency() { return currency; }
}