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

import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.ACCOUNT_NUMBER_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.ACCOUNT_TYPE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.CURRENCY_CODE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.DESTINATION_CUSTOMER_ID_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.DESTINATION_CUSTOMER_NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.DESTINATION_IBAN_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.DESTINATION_ID_TYPE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.ENTITY_CODE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.ENTITY_NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.HOLDER_ID_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.HOLDER_ID_TYPE_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.HOLDER_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.OFFICE_NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.PHONE_NUMBER_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.RESOURCE_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.TRANSFER_LIMIT_PARAM_NAME;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SelfBeneficiariesTPTDataValidator {

  private final FromJsonHelper fromApiJsonHelper;

  private static final Set<String> CREATE_REQUEST_DATA_PARAMETERS =
      new HashSet<>(
          Arrays.asList(
              SelfBeneficiariesTPTApiConstants.LOCALE,
              NAME_PARAM_NAME,
              OFFICE_NAME_PARAM_NAME,
              ACCOUNT_NUMBER_PARAM_NAME,
              ACCOUNT_TYPE_PARAM_NAME,
              TRANSFER_LIMIT_PARAM_NAME,
              DESTINATION_IBAN_PARAM_NAME,
              PHONE_NUMBER_PARAM_NAME,
              HOLDER_PARAM_NAME,
              HOLDER_ID_PARAM_NAME,
              HOLDER_ID_TYPE_PARAM_NAME,
              DESTINATION_CUSTOMER_NAME_PARAM_NAME,
              DESTINATION_CUSTOMER_ID_PARAM_NAME,
              DESTINATION_ID_TYPE_PARAM_NAME,
              CURRENCY_CODE_PARAM_NAME,
              ENTITY_CODE_PARAM_NAME,
              ENTITY_NAME_PARAM_NAME));

  private static final Set<String> UPDATE_REQUEST_DATA_PARAMETERS =
      new HashSet<>(Arrays.asList(NAME_PARAM_NAME, TRANSFER_LIMIT_PARAM_NAME));

  @Autowired
  public SelfBeneficiariesTPTDataValidator(final FromJsonHelper fromApiJsonHelper) {
    this.fromApiJsonHelper = fromApiJsonHelper;
  }

  public HashMap<String, Object> validateForCreate(String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, json, CREATE_REQUEST_DATA_PARAMETERS);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource(RESOURCE_NAME);
    final JsonElement element = this.fromApiJsonHelper.parse(json);

    final String name = this.fromApiJsonHelper.extractStringNamed(NAME_PARAM_NAME, element);
    baseDataValidator
        .reset()
        .parameter(NAME_PARAM_NAME)
        .value(name)
        .notBlank()
        .notExceedingLengthOf(50);

    final String officeName =
        this.fromApiJsonHelper.extractStringNamed(OFFICE_NAME_PARAM_NAME, element);
    baseDataValidator
        .reset()
        .parameter(OFFICE_NAME_PARAM_NAME)
        .value(officeName)
        .ignoreIfNull()
        .notExceedingLengthOf(50);

    final String accountNo =
        this.fromApiJsonHelper.extractStringNamed(ACCOUNT_NUMBER_PARAM_NAME, element);
    baseDataValidator
        .reset()
        .parameter(ACCOUNT_NUMBER_PARAM_NAME)
        .value(accountNo)
        .ignoreIfNull()
        .notExceedingLengthOf(50); // Aumentado para tolerar longitud de IBAN

    final Integer accountType =
        this.fromApiJsonHelper.extractIntegerNamed(
            ACCOUNT_TYPE_PARAM_NAME,
            element,
            this.fromApiJsonHelper.extractLocaleParameter(element.getAsJsonObject()));
    baseDataValidator
        .reset()
        .parameter(ACCOUNT_TYPE_PARAM_NAME)
        .value(accountType)
        .notNull()
        .isOneOfTheseValues(
            PortfolioAccountType.LOAN.getValue(), PortfolioAccountType.SAVINGS.getValue(), 2, 3, 4);

    final Long transferLimit =
        this.fromApiJsonHelper.extractLongNamed(TRANSFER_LIMIT_PARAM_NAME, element);
    baseDataValidator
        .reset()
        .parameter(TRANSFER_LIMIT_PARAM_NAME)
        .value(transferLimit)
        .ignoreIfNull()
        .longGreaterThanZero();

    String customAccountNumber =
        this.fromApiJsonHelper.extractStringNamed(ACCOUNT_NUMBER_PARAM_NAME, element);
    if (this.fromApiJsonHelper.parameterExists(DESTINATION_IBAN_PARAM_NAME, element)) {
      customAccountNumber =
          this.fromApiJsonHelper.extractStringNamed(DESTINATION_IBAN_PARAM_NAME, element);
    } else if (this.fromApiJsonHelper.parameterExists(PHONE_NUMBER_PARAM_NAME, element)) {
      customAccountNumber =
          this.fromApiJsonHelper.extractStringNamed(PHONE_NUMBER_PARAM_NAME, element);
    }

    String holderName = this.fromApiJsonHelper.extractStringNamed(HOLDER_PARAM_NAME, element);
    if (this.fromApiJsonHelper.parameterExists(DESTINATION_CUSTOMER_NAME_PARAM_NAME, element)) {
      holderName =
          this.fromApiJsonHelper.extractStringNamed(DESTINATION_CUSTOMER_NAME_PARAM_NAME, element);
    }

    String holderId = this.fromApiJsonHelper.extractStringNamed(HOLDER_ID_PARAM_NAME, element);
    if (this.fromApiJsonHelper.parameterExists(DESTINATION_CUSTOMER_ID_PARAM_NAME, element)) {
      holderId =
          this.fromApiJsonHelper.extractStringNamed(DESTINATION_CUSTOMER_ID_PARAM_NAME, element);
    }

    Integer holderIdType =
        this.fromApiJsonHelper.extractIntegerNamed(
            HOLDER_ID_TYPE_PARAM_NAME,
            element,
            this.fromApiJsonHelper.extractLocaleParameter(element.getAsJsonObject()));
    if (this.fromApiJsonHelper.parameterExists(DESTINATION_ID_TYPE_PARAM_NAME, element)) {
      holderIdType =
          this.fromApiJsonHelper.extractIntegerNamed(
              DESTINATION_ID_TYPE_PARAM_NAME,
              element,
              this.fromApiJsonHelper.extractLocaleParameter(element.getAsJsonObject()));
    }

    String currencyCode =
        this.fromApiJsonHelper.extractStringNamed(CURRENCY_CODE_PARAM_NAME, element);
    String entityCode = this.fromApiJsonHelper.extractStringNamed(ENTITY_CODE_PARAM_NAME, element);
    String entityName = this.fromApiJsonHelper.extractStringNamed(ENTITY_NAME_PARAM_NAME, element);

    baseDataValidator
        .reset()
        .parameter("customAccountNumber")
        .value(customAccountNumber)
        .ignoreIfNull()
        .notExceedingLengthOf(50);
    baseDataValidator
        .reset()
        .parameter("holderName")
        .value(holderName)
        .ignoreIfNull()
        .notExceedingLengthOf(150);
    baseDataValidator
        .reset()
        .parameter("holderId")
        .value(holderId)
        .ignoreIfNull()
        .notExceedingLengthOf(30);
    baseDataValidator
        .reset()
        .parameter("currencyCode")
        .value(currencyCode)
        .ignoreIfNull()
        .notExceedingLengthOf(3);
    baseDataValidator
        .reset()
        .parameter("entityCode")
        .value(entityCode)
        .ignoreIfNull()
        .notExceedingLengthOf(10);
    baseDataValidator
        .reset()
        .parameter("entityName")
        .value(entityName)
        .ignoreIfNull()
        .notExceedingLengthOf(150);

    throwExceptionIfValidationWarningsExist(dataValidationErrors);

    HashMap<String, Object> ret = new HashMap<>();
    ret.put(NAME_PARAM_NAME, name);
    ret.put(OFFICE_NAME_PARAM_NAME, officeName);
    ret.put(ACCOUNT_NUMBER_PARAM_NAME, accountNo);
    ret.put(ACCOUNT_TYPE_PARAM_NAME, accountType);
    ret.put(TRANSFER_LIMIT_PARAM_NAME, transferLimit);
    ret.put("customAccountNumber", customAccountNumber);
    ret.put("holderName", holderName);
    ret.put("holderId", holderId);
    ret.put("holderIdType", holderIdType);
    ret.put("currencyCode", currencyCode != null ? currencyCode : "CRC");
    ret.put("entityCode", entityCode);
    ret.put("entityName", entityName);

    return ret;
  }

  public HashMap<String, Object> validateForUpdate(String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, json, UPDATE_REQUEST_DATA_PARAMETERS);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource(RESOURCE_NAME);
    final JsonElement element = this.fromApiJsonHelper.parse(json);

    HashMap<String, Object> ret = new HashMap<>();

    if (this.fromApiJsonHelper.parameterExists(NAME_PARAM_NAME, element)) {
      final String name = this.fromApiJsonHelper.extractStringNamed(NAME_PARAM_NAME, element);
      baseDataValidator
          .reset()
          .parameter(NAME_PARAM_NAME)
          .value(name)
          .notBlank()
          .notExceedingLengthOf(50);
      ret.put(NAME_PARAM_NAME, name);
    }

    if (this.fromApiJsonHelper.parameterExists(TRANSFER_LIMIT_PARAM_NAME, element)) {
      final Long transferLimit =
          this.fromApiJsonHelper.extractLongNamed(TRANSFER_LIMIT_PARAM_NAME, element);
      baseDataValidator
          .reset()
          .parameter(TRANSFER_LIMIT_PARAM_NAME)
          .value(transferLimit)
          .ignoreIfNull()
          .longGreaterThanZero();
      ret.put(TRANSFER_LIMIT_PARAM_NAME, transferLimit);
    }

    throwExceptionIfValidationWarningsExist(dataValidationErrors);

    return ret;
  }

  private void throwExceptionIfValidationWarningsExist(
      final List<ApiParameterError> dataValidationErrors) {
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
  }
}
