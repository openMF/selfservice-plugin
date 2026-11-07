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

import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountTypeParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromClientIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromOfficeIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountTypeParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toClientIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toOfficeIdParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.ACCOUNT_TRANSFER_RESOURCE_NAME;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferAmountParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferDateParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferDescriptionParamName;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferReadService;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SelfAccountTransferDataValidator {

  private static final String TXN_ID_PARAM = "txnId";
  private static final String TRANSFER_TYPE_PARAM = "transferType";
  private static final String DEFAULT_TRANSFER_TYPE = "SAME_BANK";

  /** A validated transaction details request. */
  public record TransactionDetailsRequest(String txnId, String transferType) {}

  private final PlatformSelfServiceSecurityContext context;
  private final SelfAccountTransferReadService selfAccountTransferReadService;
  private final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  private final FromJsonHelper fromApiJsonHelper;

  @Autowired
  public SelfAccountTransferDataValidator(
      final PlatformSelfServiceSecurityContext context,
      final SelfAccountTransferReadService selfAccountTransferReadService,
      final SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService,
      final FromJsonHelper fromApiJsonHelper) {
    this.context = context;
    this.selfAccountTransferReadService = selfAccountTransferReadService;
    this.tptBeneficiaryReadPlatformService = tptBeneficiaryReadPlatformService;
    this.fromApiJsonHelper = fromApiJsonHelper;
  }

  /**
   * Validates the body of {@code POST /v1/self/accounttransfers/{accountId}/details}.
   *
   * <p>A numeric txnId is accepted and read as text, because transaction ids can be numeric (the
   * audit id, the Fineract transfer id or a savings transaction id).
   *
   * @param apiRequestBodyAsJson request body; must be a JSON object with a non-blank txnId
   * @return the txnId and the transferType (SAME_BANK when missing or blank)
   * @throws InvalidJsonException when the body is missing or is not a JSON object
   * @throws PlatformApiDataValidationException when txnId is missing or blank, or is an object or
   *     array
   */
  public TransactionDetailsRequest validateTransactionDetails(final String apiRequestBodyAsJson) {
    if (StringUtils.isBlank(apiRequestBodyAsJson)) {
      throw new InvalidJsonException();
    }
    final JsonElement element = this.fromApiJsonHelper.parse(apiRequestBodyAsJson);
    if (element == null || !element.isJsonObject()) {
      throw new InvalidJsonException();
    }

    // extractStringNamed returns null for a missing or blank value and for an object or array.
    final String txnId = this.fromApiJsonHelper.extractStringNamed(TXN_ID_PARAM, element);
    validateTransactionId(txnId);

    final String transferType =
        this.fromApiJsonHelper.extractStringNamed(TRANSFER_TYPE_PARAM, element);
    return new TransactionDetailsRequest(txnId, resolveTransferType(transferType));
  }

  /**
   * Returns the transfer type to look a transaction up with: SAME_BANK when it is null or blank,
   * otherwise the given value.
   */
  public String resolveTransferType(final String transferType) {
    return StringUtils.isBlank(transferType) ? DEFAULT_TRANSFER_TYPE : transferType;
  }

  /**
   * Validates a transaction id, such as the {@code txnId} path parameter of {@code GET
   * /v1/self/accounttransfers/{accountId}/details/{txnId}}.
   *
   * @throws PlatformApiDataValidationException when txnId is null or blank
   */
  public void validateTransactionId(final String txnId) {
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    new DataValidatorBuilder(dataValidationErrors)
        .resource(ACCOUNT_TRANSFER_RESOURCE_NAME)
        .parameter(TXN_ID_PARAM)
        .value(txnId)
        .notBlank();
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
  }

  public Map<String, Object> validateCreate(String type, String apiRequestBodyAsJson) {
    if (StringUtils.isBlank(apiRequestBodyAsJson)) {
      throw new InvalidJsonException();
    }

    JsonElement element = this.fromApiJsonHelper.parse(apiRequestBodyAsJson);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource(ACCOUNT_TRANSFER_RESOURCE_NAME);

    final Long fromOfficeId =
        this.fromApiJsonHelper.extractLongNamed(fromOfficeIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(fromOfficeIdParamName)
        .value(fromOfficeId)
        .notNull()
        .integerGreaterThanZero();

    final Long fromClientId =
        this.fromApiJsonHelper.extractLongNamed(fromClientIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(fromClientIdParamName)
        .value(fromClientId)
        .notNull()
        .integerGreaterThanZero();

    final Long fromAccountId =
        this.fromApiJsonHelper.extractLongNamed(fromAccountIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(fromAccountIdParamName)
        .value(fromAccountId)
        .notNull()
        .integerGreaterThanZero();

    final Integer fromAccountType =
        this.fromApiJsonHelper.extractIntegerSansLocaleNamed(fromAccountTypeParamName, element);
    baseDataValidator
        .reset()
        .parameter(fromAccountTypeParamName)
        .value(fromAccountType)
        .notNull()
        .isOneOfTheseValues(Integer.valueOf(1), Integer.valueOf(2));

    final Long toOfficeId = this.fromApiJsonHelper.extractLongNamed(toOfficeIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(toOfficeIdParamName)
        .value(toOfficeId)
        .notNull()
        .integerGreaterThanZero();

    final Long toClientId = this.fromApiJsonHelper.extractLongNamed(toClientIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(toClientIdParamName)
        .value(toClientId)
        .notNull()
        .integerGreaterThanZero();

    final Long toAccountId = this.fromApiJsonHelper.extractLongNamed(toAccountIdParamName, element);
    baseDataValidator
        .reset()
        .parameter(toAccountIdParamName)
        .value(toAccountId)
        .notNull()
        .integerGreaterThanZero();

    final Integer toAccountType =
        this.fromApiJsonHelper.extractIntegerSansLocaleNamed(toAccountTypeParamName, element);
    baseDataValidator
        .reset()
        .parameter(toAccountTypeParamName)
        .value(toAccountType)
        .notNull()
        .isOneOfTheseValues(Integer.valueOf(1), Integer.valueOf(2));

    if (fromAccountType != null
        && fromAccountType == 1
        && toAccountType != null
        && toAccountType == 1) {
      baseDataValidator
          .reset()
          .failWithCode(
              "loan.to.loan.transfer.not.allowed",
              "Cannot transfer from Loan account to another Loan account.");
    }

    final LocalDate transactionDate =
        this.fromApiJsonHelper.extractLocalDateNamed(transferDateParamName, element);
    baseDataValidator.reset().parameter(transferDateParamName).value(transactionDate).notNull();

    final BigDecimal transactionAmount =
        this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed(transferAmountParamName, element);
    baseDataValidator
        .reset()
        .parameter(transferAmountParamName)
        .value(transactionAmount)
        .notNull()
        .positiveAmount();

    final String transactionDescription =
        this.fromApiJsonHelper.extractStringNamed(transferDescriptionParamName, element);
    baseDataValidator
        .reset()
        .parameter(transferDescriptionParamName)
        .value(transactionDescription)
        .notBlank()
        .notExceedingLengthOf(200);

    throwExceptionIfValidationWarningsExist(dataValidationErrors);

    SelfAccountTemplateData fromAccount =
        new SelfAccountTemplateData(fromAccountId, fromAccountType, fromClientId, fromOfficeId);
    SelfAccountTemplateData toAccount =
        new SelfAccountTemplateData(toAccountId, toAccountType, toClientId, toOfficeId);

    validateUserAccounts(fromAccount, toAccount, baseDataValidator, type);
    throwExceptionIfValidationWarningsExist(dataValidationErrors);

    Map<String, Object> ret = new HashMap<>();
    ret.put("fromAccount", fromAccount);
    ret.put("toAccount", toAccount);
    ret.put("transactionDate", transactionDate);
    ret.put("transactionAmount", transactionAmount);

    return ret;
  }

  private void validateUserAccounts(
      final SelfAccountTemplateData fromAccount,
      final SelfAccountTemplateData toAccount,
      final DataValidatorBuilder baseDataValidator,
      final String type) {
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    Collection<SelfAccountTemplateData> validFromAccounts =
        this.selfAccountTransferReadService.retrieveSelfAccountTemplateData(user);

    Collection<SelfAccountTemplateData> validToAccounts = validFromAccounts;
    if ("tpt".equals(type)) {
      validToAccounts =
          this.tptBeneficiaryReadPlatformService.retrieveTPTSelfAccountTemplateData(user);
    }

    boolean validFromAccount = false;
    for (SelfAccountTemplateData validAccount : validFromAccounts) {
      if (validAccount.equals(fromAccount)) {
        validFromAccount = true;
        break;
      }
    }

    boolean validToAccount = false;
    for (SelfAccountTemplateData validAccount : validToAccounts) {
      if (validAccount.equals(toAccount)) {
        validToAccount = true;
        break;
      }
    }

    if (!validFromAccount) {
      baseDataValidator
          .reset()
          .failWithCode(
              "invalid.from.account.details",
              "Source account details doesn't match with valid user account details.");
    }

    if (!validToAccount) {
      baseDataValidator
          .reset()
          .failWithCode(
              "invalid.to.account.details",
              "Destination account details doesn't match with valid user account details.");
    }

    if (fromAccount.equals(toAccount)) {
      baseDataValidator
          .reset()
          .failWithCode(
              "same.from.to.account.details", "Source and Destination account details are same.");
    }
  }

  private void throwExceptionIfValidationWarningsExist(
      final List<ApiParameterError> dataValidationErrors) {
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
  }
}
