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
package org.apache.fineract.selfservice.pockets.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.pockets.api.PocketApiConstants;
import org.springframework.stereotype.Service;

/** Validates the JSON payloads used to link accounts to, and delink accounts from, a pocket. */
@Service
@RequiredArgsConstructor
public class PocketDataValidator {

  private static final Set<String> LINKING_ACCOUNTS_SUPPORTED_PARAMETERS =
      Set.of(PocketApiConstants.accountsDetail);

  private static final Set<String> ACCOUNT_DETAIL_SUPPORTED_PARAMETERS =
      Set.of(PocketApiConstants.accountIdParamName, PocketApiConstants.accountTypeParamName);

  private static final Set<String> DELINKING_ACCOUNTS_SUPPORTED_PARAMETERS =
      Set.of(PocketApiConstants.pocketAccountMappingList);

  private final FromJsonHelper fromApiJsonHelper;

  public void validateForLinkingAccounts(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, json, LINKING_ACCOUNTS_SUPPORTED_PARAMETERS);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(PocketApiConstants.pocketsResourceName);

    final JsonElement element = this.fromApiJsonHelper.parse(json);

    JsonArray accountsDetail =
        this.fromApiJsonHelper.extractJsonArrayNamed(PocketApiConstants.accountsDetail, element);
    baseDataValidator
        .reset()
        .parameter(PocketApiConstants.accountsDetail)
        .value(accountsDetail)
        .notNull()
        .jsonArrayNotEmpty();

    final List<String> valueList =
        Arrays.asList(
            EntityAccountType.LOAN.name().toLowerCase(),
            EntityAccountType.SAVINGS.name().toLowerCase(),
            EntityAccountType.SHARES.name().toLowerCase());

    if (accountsDetail != null) {
      for (JsonElement accountDetails : accountsDetail) {
        this.fromApiJsonHelper.checkForUnsupportedParameters(
            accountDetails.getAsJsonObject(), ACCOUNT_DETAIL_SUPPORTED_PARAMETERS);

        final Long accountId =
            this.fromApiJsonHelper.extractLongNamed(
                PocketApiConstants.accountIdParamName, accountDetails);
        baseDataValidator
            .reset()
            .parameter(PocketApiConstants.accountIdParamName)
            .value(accountId)
            .notBlank();

        final String accountType =
            this.fromApiJsonHelper.extractStringNamed(
                PocketApiConstants.accountTypeParamName, accountDetails);
        // Account type is matched case-insensitively; the Swagger example uses upper case
        // (e.g. "LOAN") while the enum names are compared in lower case here.
        final String normalizedAccountType = accountType == null ? null : accountType.toLowerCase();
        baseDataValidator
            .reset()
            .parameter(PocketApiConstants.accountTypeParamName)
            .value(normalizedAccountType)
            .notBlank()
            .isOneOfTheseStringValues(valueList);
      }
    }

    throwExceptionIfValidationWarningsExist(dataValidationErrors);
  }

  public void validateForDeLinkingAccounts(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, json, DELINKING_ACCOUNTS_SUPPORTED_PARAMETERS);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(PocketApiConstants.pocketsResourceName);

    final JsonElement element = this.fromApiJsonHelper.parse(json);

    JsonArray pocketAccountMappingList =
        this.fromApiJsonHelper.extractJsonArrayNamed(
            PocketApiConstants.pocketAccountMappingList, element);
    baseDataValidator
        .reset()
        .parameter(PocketApiConstants.pocketAccountMappingList)
        .value(pocketAccountMappingList)
        .notNull()
        .jsonArrayNotEmpty();

    if (pocketAccountMappingList != null) {
      for (JsonElement pocketAccountMapping : pocketAccountMappingList) {

        // Guard against non-numeric elements (e.g. {}, null, "abc") so a malformed payload
        // surfaces as a client validation error instead of a runtime getAsLong() failure.
        Long mappingId = null;
        if (pocketAccountMapping != null
            && pocketAccountMapping.isJsonPrimitive()
            && pocketAccountMapping.getAsJsonPrimitive().isNumber()) {
          mappingId = pocketAccountMapping.getAsLong();
        }
        baseDataValidator
            .reset()
            .parameter(PocketApiConstants.pocketAccountMappingId)
            .value(mappingId)
            .notBlank();
      }
    }

    throwExceptionIfValidationWarningsExist(dataValidationErrors);
  }

  private void throwExceptionIfValidationWarningsExist(
      final List<ApiParameterError> dataValidationErrors) {
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(
          PocketApiConstants.dataValidationMessage,
          PocketApiConstants.validationErrorMessage,
          dataValidationErrors);
    }
  }
}
