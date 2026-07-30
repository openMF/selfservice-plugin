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
public class SelfBeneficiariesTPTWritePlatformServiceImpl
        implements SelfBeneficiariesTPTWritePlatformService {

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

        log.info(
                "Creating TPT beneficiary: name={}, paymentType={}, currency={}, accountType={}, transferLimit={}",
                name,
                paymentType,
                currency,
                accountType,
                transferLimit);
        log.info(
                "Beneficiary create request details: officeName={}, accountNumberLength={}",
                officeName,
                accountNumber != null ? accountNumber.length() : 0);

        Long accountId = null;
        Long clientId = null;
        Long officeId = null;
        boolean validAccountDetails = true;

        // Route logic based on explicit paymentType rather than magic numbers
        if ("SAME_BANK".equals(paymentType)) {
            log.info("Resolving SAME_BANK beneficiary against internal Fineract accounts");
            if (accountType.equals(PortfolioAccountType.LOAN.getValue())) {
                Loan loan = this.loanRepositoryWrapper.findNonClosedLoanByAccountNumber(accountNumber);
                if (loan != null
                        && loan.getClientId() != null
                        && loan.getOffice().getName().equals(officeName)) {
                    accountId = loan.getId();
                    officeId = loan.getOfficeId();
                    clientId = loan.getClientId();
                    log.info(
                            "Resolved loan beneficiary: loanId={}, clientId={}, officeId={}",
                            accountId,
                            clientId,
                            officeId);
                } else {
                    validAccountDetails = false;
                    log.warn(
                            "SAME_BANK loan lookup failed for accountNumber (len={}), officeName={}",
                            accountNumber != null ? accountNumber.length() : 0,
                            officeName);
                }
            } else {
                SavingsAccount savings =
                        this.savingRepositoryWrapper.findNonClosedAccountByAccountNumber(accountNumber);
                if (savings != null
                        && savings.getClient() != null
                        && savings.getClient().getOffice().getName().equals(officeName)) {
                    accountId = savings.getId();
                    clientId = savings.getClient().getId();
                    officeId = savings.getClient().getOffice().getId();
                    log.info(
                            "Resolved savings beneficiary: savingsId={}, clientId={}, officeId={}",
                            accountId,
                            clientId,
                            officeId);
                } else {
                    validAccountDetails = false;
                    log.warn(
                            "SAME_BANK savings lookup failed for accountNumber (len={}), officeName={}",
                            accountNumber != null ? accountNumber.length() : 0,
                            officeName);
                }
            }
        } else if ("SINPE".equals(paymentType) || "PIN".equals(paymentType)) {
            // External beneficiaries: length/format validation is already guaranteed by SelfBeneficiariesTPTDataValidator
            validAccountDetails = (accountNumber != null && !accountNumber.trim().isEmpty());
            log.info(
                    "Processing external {} beneficiary, accountNumber present={}",
                    paymentType,
                    validAccountDetails);

            // Use 0L for internal IDs to satisfy legacy NOT NULL constraints on external beneficiaries
            officeId = 0L;
            clientId = 0L;
            accountId = 0L;
        } else {
            validAccountDetails = false;
            log.warn("Unsupported paymentType received: {}", paymentType);
        }

        if (!validAccountDetails) {
            log.warn(
                    "Rejecting beneficiary create due to invalid account details: paymentType={}, officeName={}",
                    paymentType,
                    officeName);
            throw new InvalidAccountInformationException(officeName, accountNumber, paymentType);
        }

        try {
            AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
            Long appUserId = user.getId();
            log.info("Authenticated self-service userId={} creating beneficiary", appUserId);

            // Constructor now includes paymentType and currency
            SelfBeneficiariesTPT beneficiary =
                    new SelfBeneficiariesTPT(
                            appUserId,
                            name,
                            officeId,
                            clientId,
                            accountId,
                            accountType,
                            transferLimit,
                            paymentType,
                            currency);

            // Populate external-specific fields only when applicable
            if ("SINPE".equals(paymentType) || "PIN".equals(paymentType)) {
                beneficiary.setCustomAccountNumber(accountNumber);
                beneficiary.setHolderName((String) params.get("holderName"));
                beneficiary.setHolderId((String) params.get("holderId"));
                beneficiary.setHolderIdType((Integer) params.get("holderIdType"));
                beneficiary.setCurrencyCode((String) params.get("currencyCode"));
                beneficiary.setEntityCode((String) params.get("entityCode"));
                beneficiary.setEntityName((String) params.get("entityName"));
                log.info(
                        "Populated external fields for {}: entityCode={}, currencyCode={}",
                        paymentType,
                        params.get("entityCode"),
                        params.get("currencyCode"));
            }

            beneficiary.setActive(true);
            this.repository.saveAndFlush(beneficiary);

            log.info(
                    "Successfully created TPT beneficiary id={}, name={}, paymentType={}, userId={}",
                    beneficiary.getId(),
                    name,
                    paymentType,
                    appUserId);

            return new CommandProcessingResultBuilder().withEntityId(beneficiary.getId()).build();

        } catch (DataAccessException dae) {
            log.error(
                    "Data integrity failure while creating beneficiary name={}, paymentType={}",
                    name,
                    paymentType,
                    dae);
            handleDataIntegrityIssues(command, dae);
        }

        // Fallback exception (should ideally not be reached due to prior checks)
        log.error(
                "Unexpected fallback path reached for beneficiary create: name={}, paymentType={}",
                name,
                paymentType);
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
        log.info("Updating TPT beneficiary id={}", beneficiaryId);

        Map<String, Object> params = this.validator.validateForUpdate(command.json());
        AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();

        // Java 21 idiomatic retrieval with immediate security validation
        SelfBeneficiariesTPT beneficiary =
                this.repository
                        .findById(beneficiaryId)
                        .orElseThrow(
                                () -> {
                                    log.warn("Beneficiary not found for update: id={}", beneficiaryId);
                                    return new InvalidBeneficiaryException(beneficiaryId);
                                });

        if (!beneficiary.getAppSelfServiceUserId().equals(user.getId())) {
            log.warn(
                    "User id={} attempted to update beneficiary id={} owned by userId={}",
                    user.getId(),
                    beneficiaryId,
                    beneficiary.getAppSelfServiceUserId());
            throw new InvalidBeneficiaryException(beneficiaryId);
        }

        String name = (String) params.get(NAME_PARAM_NAME);
        Long transferLimit = (Long) params.get(TRANSFER_LIMIT_PARAM_NAME);
        log.info(
                "Update payload for beneficiary id={}: name={}, transferLimit={}",
                beneficiaryId,
                name,
                transferLimit);

        Map<String, Object> changes = beneficiary.update(name, transferLimit);
        if (!changes.isEmpty()) {
            try {
                this.repository.saveAndFlush(beneficiary);
                log.info(
                        "Successfully updated TPT beneficiary id={}, changes={}",
                        beneficiary.getId(),
                        changes.keySet());
                return new CommandProcessingResultBuilder()
                        .withEntityId(beneficiary.getId())
                        .with(changes)
                        .build();
            } catch (DataAccessException dae) {
                log.error(
                        "Data integrity failure while updating beneficiary id={}",
                        beneficiaryId,
                        dae);
                handleDataIntegrityIssues(command, dae);
            }
        } else {
            log.info("No changes detected for beneficiary id={}", beneficiaryId);
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
        log.info("Soft-deleting TPT beneficiary id={}", beneficiaryId);

        AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();

        SelfBeneficiariesTPT beneficiary =
                this.repository
                        .findById(beneficiaryId)
                        .orElseThrow(
                                () -> {
                                    log.warn("Beneficiary not found for delete: id={}", beneficiaryId);
                                    return new InvalidBeneficiaryException(beneficiaryId);
                                });

        if (!beneficiary.getAppSelfServiceUserId().equals(user.getId())) {
            log.warn(
                    "User id={} attempted to delete beneficiary id={} owned by userId={}",
                    user.getId(),
                    beneficiaryId,
                    beneficiary.getAppSelfServiceUserId());
            throw new InvalidBeneficiaryException(beneficiaryId);
        }

        beneficiary.setActive(false);
        this.repository.save(beneficiary);

        log.info(
                "Successfully soft-deleted TPT beneficiary id={}, previousName={}, userId={}",
                beneficiary.getId(),
                beneficiary.getName(),
                user.getId());

        return new CommandProcessingResultBuilder().withEntityId(beneficiary.getId()).build();
    }

    private void handleDataIntegrityIssues(final JsonCommand command, final DataAccessException dae) {
        final Throwable realCause = dae.getMostSpecificCause();
        String message = realCause.getMessage();

        if (message != null
                && (message.contains("name")
                        || message.contains("uk_m_selfservice_beneficiaries_tpt_name"))) {
            String name = "unknown";
            try {
                if (command != null && command.json() != null) {
                    name = command.stringValueOfParameterNamed(NAME_PARAM_NAME);
                }
            } catch (Exception e) {
                log.info("Could not extract name from JSON command during integrity handling", e);
            }

            log.warn("Duplicate beneficiary name detected: name={}", name);
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