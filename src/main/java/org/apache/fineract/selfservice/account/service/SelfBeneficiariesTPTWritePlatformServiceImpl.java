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
package org.apache.fineract.selfservice.account.service;

import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.ACCOUNT_NUMBER_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.ACCOUNT_TYPE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.CURRENCY_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.OFFICE_NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.PAYMENT_TYPE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.TRANSFER_LIMIT_PARAM_NAME;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.selfservice.account.data.SelfBeneficiariesTPTDataValidator;
import org.apache.fineract.selfservice.account.domain.SelfBeneficiariesTPT;
import org.apache.fineract.selfservice.account.domain.SelfBeneficiariesTPTRepository;
import org.apache.fineract.selfservice.account.exception.InvalidAccountInformationException;
import org.apache.fineract.selfservice.account.exception.InvalidBeneficiaryException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link SelfBeneficiariesTPTWritePlatformService} handling the business logic
 * for creating, updating, and deleting self-service beneficiaries.
 */
@RequiredArgsConstructor
@Slf4j
public class SelfBeneficiariesTPTWritePlatformServiceImpl implements SelfBeneficiariesTPTWritePlatformService {

    private final PlatformSelfServiceSecurityContext context;
    private final SelfBeneficiariesTPTRepository repository;
    private final SelfBeneficiariesTPTDataValidator validator;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final SavingsAccountRepositoryWrapper savingRepositoryWrapper;

    /**
     * Adds a new self-service beneficiary.
     *
     * @param command the JSON command containing beneficiary details
     * @return the result containing the generated entity ID
     */
    @Transactional
    @Override
    public CommandProcessingResult add(JsonCommand command) {
        // Validator ensures paymentType is "SAME_BANK", "SINPE", or "PIN" and validates lengths/formats
        Map<String, Object> params = this.validator.validateForCreate(command.json());

        String name = (String) params.get(NAME_PARAM_NAME);
        Integer accountType = (Integer) params.get(ACCOUNT_TYPE_PARAM_NAME);
        String accountNumber = (String) params.get(ACCOUNT_NUMBER_PARAM_NAME);
        String officeName = (String) params.get(OFFICE_NAME_PARAM_NAME);
        Long transferLimit = (Long) params.get(TRANSFER_LIMIT_PARAM_NAME);
        String paymentType = (String) params.get(PAYMENT_TYPE_PARAM_NAME);
        String currency = (String) params.get(CURRENCY_PARAM_NAME);

        Long accountId = null;
        Long clientId = null;
        Long officeId = null;
        boolean validAccountDetails = true;

        // Route logic based on explicit paymentType rather than magic numbers
        if ("SAME_BANK".equals(paymentType)) {
            if (accountType.equals(PortfolioAccountType.LOAN.getValue())) {
                Loan loan = this.loanRepositoryWrapper.findNonClosedLoanByAccountNumber(accountNumber);
                if (loan != null && loan.getClientId() != null && loan.getOffice().getName().equals(officeName)) {
                    accountId = loan.getId();
                    officeId = loan.getOfficeId();
                    clientId = loan.getClientId();
                } else {
                    validAccountDetails = false;
                }
            } else {
                SavingsAccount savings = this.savingRepositoryWrapper.findNonClosedAccountByAccountNumber(accountNumber);
                if (savings != null && savings.getClient() != null && savings.getClient().getOffice().getName().equals(officeName)) {
                    accountId = savings.getId();
                    clientId = savings.getClient().getId();
                    officeId = savings.getClient().getOffice().getId();
                } else {
                    validAccountDetails = false;
                }
            }
        } else if ("SINPE".equals(paymentType) || "PIN".equals(paymentType)) {
            // External beneficiaries: length/format validation is already guaranteed by SelfBeneficiariesTPTDataValidator
            validAccountDetails = (accountNumber != null && !accountNumber.trim().isEmpty());
            
            // Use 0L for internal IDs to satisfy legacy NOT NULL constraints on external beneficiaries
            officeId = 0L;
            clientId = 0L;
            accountId = 0L;
        } else {
            validAccountDetails = false;
        }

        if (!validAccountDetails) {
            throw new InvalidAccountInformationException(officeName, accountNumber, paymentType);
        }

        try {
            AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
            Long appUserId = user.getId();

            // Constructor now includes paymentType and currency
            SelfBeneficiariesTPT beneficiary = new SelfBeneficiariesTPT(
                    appUserId, name, officeId, clientId, accountId, accountType, transferLimit, paymentType, currency);

            // Populate external-specific fields only when applicable
            if ("SINPE".equals(paymentType) || "PIN".equals(paymentType)) {
                beneficiary.setCustomAccountNumber(accountNumber);
                beneficiary.setHolderName((String) params.get("holderName"));
                beneficiary.setHolderId((String) params.get("holderId"));
                beneficiary.setHolderIdType((Integer) params.get("holderIdType"));
                beneficiary.setCurrencyCode((String) params.get("currencyCode"));
                beneficiary.setEntityCode((String) params.get("entityCode"));
                beneficiary.setEntityName((String) params.get("entityName"));
            }

            beneficiary.setActive(true);
            this.repository.saveAndFlush(beneficiary);
            
            return new CommandProcessingResultBuilder().withEntityId(beneficiary.getId()).build();
            
        } catch (DataAccessException dae) {
            handleDataIntegrityIssues(command, dae);
        }

        // Fallback exception (should ideally not be reached due to prior checks)
        throw new InvalidAccountInformationException(officeName, accountNumber, paymentType);
    }

    /**
     * Updates an existing self-service beneficiary.
     *
     * @param beneficiaryId the ID of the beneficiary to update
     * @param command the JSON command containing the fields to update
     * @return the result containing the entity ID and the changes made
     */
    @Transactional
    @Override
    public CommandProcessingResult update(Long beneficiaryId, JsonCommand command) {
        Map<String, Object> params = this.validator.validateForUpdate(command.json());
        AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
        
        // Java 21 idiomatic retrieval with immediate security validation
        SelfBeneficiariesTPT beneficiary = this.repository.findById(beneficiaryId)
                .orElseThrow(() -> new InvalidBeneficiaryException(beneficiaryId));

        if (!beneficiary.getAppSelfServiceUserId().equals(user.getId())) {
            throw new InvalidBeneficiaryException(beneficiaryId);
        }

        String name = (String) params.get(NAME_PARAM_NAME);
        Long transferLimit = (Long) params.get(TRANSFER_LIMIT_PARAM_NAME);

        Map<String, Object> changes = beneficiary.update(name, transferLimit);
        if (!changes.isEmpty()) {
            try {
                this.repository.saveAndFlush(beneficiary);
                return new CommandProcessingResultBuilder()
                        .withEntityId(beneficiary.getId())
                        .with(changes)
                        .build();
            } catch (DataAccessException dae) {
                handleDataIntegrityIssues(command, dae);
            }
        }
        
        return new CommandProcessingResultBuilder().withEntityId(beneficiary.getId()).build();
    }

    /**
     * Deallocates or softly deletes a self-service beneficiary.
     *
     * @param beneficiaryId the ID of the beneficiary to delete
     * @param command the JSON command requesting deletion
     * @return the result containing the deleted entity ID
     */
    @Transactional
    @Override
    public CommandProcessingResult delete(Long beneficiaryId, JsonCommand command) {
        AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
        
        SelfBeneficiariesTPT beneficiary = this.repository.findById(beneficiaryId)
                .orElseThrow(() -> new InvalidBeneficiaryException(beneficiaryId));

        if (!beneficiary.getAppSelfServiceUserId().equals(user.getId())) {
            throw new InvalidBeneficiaryException(beneficiaryId);
        }

        beneficiary.setActive(false);
        this.repository.save(beneficiary);

        return new CommandProcessingResultBuilder()
                .withEntityId(beneficiary.getId())
                .build();
    }

    private void handleDataIntegrityIssues(final JsonCommand command, final DataAccessException dae) {
        final Throwable realCause = dae.getMostSpecificCause();
        String message = realCause.getMessage();
        
        if (message != null && (message.contains("name") || message.contains("uk_m_selfservice_beneficiaries_tpt_name"))) {
            String name = "unknown";
            try {
                if (command != null && command.json() != null) {
                    name = command.stringValueOfParameterNamed(NAME_PARAM_NAME);
                }
            } catch (Exception e) {
                log.debug("Could not extract name from JSON command", e);
            }

            throw new PlatformDataIntegrityException(
                    "error.msg.beneficiary.duplicate.name",
                    "Beneficiary with name `" + name + "` already exists for this user.",
                    NAME_PARAM_NAME,
                    name);
        }

        log.error("Unexpected data integrity issue with beneficiary", dae);
        throw ErrorHandler.getMappable(
                dae,
                "error.msg.beneficiary.unknown.data.integrity.issue",
                "Unknown data integrity issue with beneficiary resource.");
    }
}