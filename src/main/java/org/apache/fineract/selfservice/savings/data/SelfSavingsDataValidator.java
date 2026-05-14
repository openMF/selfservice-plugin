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
package org.apache.fineract.selfservice.savings.data;

import com.google.gson.JsonElement;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SelfSavingsDataValidator {

  private final FromJsonHelper fromApiJsonHelper;

  @Autowired
  public SelfSavingsDataValidator(final FromJsonHelper fromApiJsonHelper) {
    this.fromApiJsonHelper = fromApiJsonHelper;
  }

  private static final Set<String> allowedAssociationParameters =
      new HashSet<>(Arrays.asList(SavingsApiConstants.transactions, SavingsApiConstants.charges));

  public void validateRetrieveSavings(final UriInfo uriInfo) {
    List<String> unsupportedParams = new ArrayList<>();

    validateTemplate(uriInfo, unsupportedParams);

    Set<String> associationParameters =
        ApiParameterHelper.extractAssociationsForResponseIfProvided(uriInfo.getQueryParameters());
    if (!associationParameters.isEmpty()) {
      associationParameters.removeAll(allowedAssociationParameters);
      if (!associationParameters.isEmpty()) {
        unsupportedParams.addAll(associationParameters);
      }
    }

    if (uriInfo.getQueryParameters().getFirst("exclude") != null) {
      unsupportedParams.add("exclude");
    }

    throwExceptionIfReqd(unsupportedParams);

    // New validation for month and year
    final MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();

    String monthStr = queryParams.getFirst("month");
    String yearStr = queryParams.getFirst("year");

    if (monthStr != null || yearStr != null) {
      if (monthStr == null || yearStr == null) {
        throw new PlatformApiDataValidationException(
            "validation.msg.savingsaccount.month.and.year.both.required",
            "Both 'month' and 'year' parameters are required when filtering by date.",
            "month",
            "year");
      }

      Integer month = Integer.valueOf(monthStr);
      Integer year = Integer.valueOf(yearStr);

      if (month < 1 || month > 12) {
        throw new PlatformApiDataValidationException(
            "validation.msg.savingsaccount.invalid.month",
            "Month must be between 1 and 12.",
            "month");
      }

      if (year < 2000 || year > 2100) { // reasonable range
        throw new PlatformApiDataValidationException(
            "validation.msg.savingsaccount.invalid.year",
            "Year must be between 2000 and 2100.",
            "year");
      }
    }
  }

  public void validateRetrieveSavingsTransaction(final UriInfo uriInfo) {
    List<String> unsupportedParams = new ArrayList<>();

    validateTemplate(uriInfo, unsupportedParams);

    throwExceptionIfReqd(unsupportedParams);
  }

  private void throwExceptionIfReqd(final List<String> unsupportedParams) {
    if (unsupportedParams.size() > 0) {
      throw new UnsupportedParameterException(unsupportedParams);
    }
  }

  private void validateTemplate(final UriInfo uriInfo, List<String> unsupportedParams) {
    final boolean templateRequest = ApiParameterHelper.template(uriInfo.getQueryParameters());
    if (templateRequest) {
      unsupportedParams.add("template");
    }
  }

  public HashMap<String, Object> validateSavingsApplication(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(SelfSavingsAccountConstants.savingsAccountResource);

    final JsonElement element = this.fromApiJsonHelper.parse(json);

    final Long clientId =
        this.fromApiJsonHelper.extractLongNamed(
            SelfSavingsAccountConstants.clientIdParameterName, element);
    baseDataValidator
        .reset()
        .parameter(SelfSavingsAccountConstants.clientIdParameterName)
        .value(clientId)
        .notNull()
        .longGreaterThanZero();

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    HashMap<String, Object> parameterMap = new HashMap<>();
    parameterMap.put(SelfSavingsAccountConstants.clientIdParameterName, clientId);

    return parameterMap;
  }
}
