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
package org.apache.fineract.selfservice.loanaccount.data;

import com.google.gson.JsonElement;
import jakarta.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SelfLoansDataValidator {

  private static final Set<String> allowedAssociationParameters =
      new HashSet<>(
          Arrays.asList(
              "repaymentSchedule",
              "futureSchedule",
              "originalSchedule",
              "transactions",
              "charges",
              "guarantors",
              "collateral",
              "linkedAccount",
              "multiDisburseDetails"));
  private final FromJsonHelper fromApiJsonHelper;

  @Autowired
  public SelfLoansDataValidator(final FromJsonHelper fromApiJsonHelper) {
    this.fromApiJsonHelper = fromApiJsonHelper;
  }

  public void validateRetrieveLoan(final UriInfo uriInfo) {
    List<String> unsupportedParams = new ArrayList<>();

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
  }

  public void validateRetrieveTransaction(UriInfo uriInfo) {
    List<String> unsupportedParams = new ArrayList<>();

    validateTemplate(uriInfo, unsupportedParams);

    throwExceptionIfReqd(unsupportedParams);
  }

  public void validateRetrieveAllLoanTransactions(UriInfo uriInfo, String transactionType) {
    List<String> unsupportedParams = new ArrayList<>();

    validateTemplate(uriInfo, unsupportedParams);

    if (StringUtils.isNotBlank(transactionType)) {
      Set<String> allowedTypes = getAllowedTransactionTypes();
      List<String> requestedTypes = Arrays.stream(transactionType.split(","))
          .map(String::trim)
          .filter(StringUtils::isNotBlank)
          .collect(Collectors.toList());
      for (String type : requestedTypes) {
        if (!allowedTypes.contains(type.toLowerCase())) {
          unsupportedParams.add("transactionType value '" + type + "' is not supported. "
              + "Supported values: " + String.join(", ", allowedTypes));
        }
      }
    }

    throwExceptionIfReqd(unsupportedParams);
  }

  private Set<String> getAllowedTransactionTypes() {
    return new HashSet<>(
        Arrays.asList(
            "disbursement",
            "repayment",
            "repaymentatdisbursement",
            "charge",
            "fee",
            "waiver",
            "writeoff",
            "recoveryrepayment",
            "accrual",
            "contra"));
  }

  public HashMap<String, Object> validateLoanApplication(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("loan");

    final JsonElement element = this.fromApiJsonHelper.parse(json);

    final String loanTypeParameterName = "loanType";
    final String loanTypeStr =
        this.fromApiJsonHelper.extractStringNamed(loanTypeParameterName, element);
    baseDataValidator
        .reset()
        .parameter(loanTypeParameterName)
        .value(loanTypeStr)
        .notNull()
        .isOneOfTheseStringValues("individual");

    final String clientIdParameterName = "clientId";
    final String clientId =
        this.fromApiJsonHelper.extractStringNamed(clientIdParameterName, element);
    baseDataValidator
        .reset()
        .parameter(clientIdParameterName)
        .value(clientId)
        .notNull()
        .longGreaterThanZero();

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    HashMap<String, Object> retAttr = new HashMap<>();
    retAttr.put("clientId", Long.parseLong(clientId));

    return retAttr;
  }

  public HashMap<String, Object> validateModifyLoanApplication(final String json) {
    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors).resource("loan");

    final JsonElement element = this.fromApiJsonHelper.parse(json);
    // A blank body parses to null, and valid JSON that isn't an object (e.g. [], "x", 123) would
    // make parameterExists() throw IllegalStateException, which surfaces as a 500.
    if (element == null || !element.isJsonObject()) {
      throw new InvalidJsonException();
    }

    final String loanTypeParameterName = "loanType";
    if (this.fromApiJsonHelper.parameterExists(loanTypeParameterName, element)) {
      final String loanTypeStr =
          this.fromApiJsonHelper.extractStringNamed(loanTypeParameterName, element);
      baseDataValidator
          .reset()
          .parameter(loanTypeParameterName)
          .value(loanTypeStr)
          .notNull()
          .isOneOfTheseStringValues("individual");
    }

    final String clientIdParameterName = "clientId";
    String clientId = null;
    if (this.fromApiJsonHelper.parameterExists(clientIdParameterName, element)) {
      clientId = this.fromApiJsonHelper.extractStringNamed(clientIdParameterName, element);
      baseDataValidator
          .reset()
          .parameter(clientIdParameterName)
          .value(clientId)
          .notNull()
          .longGreaterThanZero();
    }

    // Fineract refuses a group for an individual loan on create, but on modify it attaches the loan
    // to the group given in the body.
    final String groupIdParameterName = "groupId";
    if (this.fromApiJsonHelper.parameterExists(groupIdParameterName, element)) {
      dataValidationErrors.add(
          ApiParameterError.parameterError(
              "validation.msg.loan.groupId.not.allowed",
              "A self-service loan cannot belong to a group.",
              groupIdParameterName));
    }

    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    HashMap<String, Object> retAttr = new HashMap<>();
    if (clientId != null) {
      retAttr.put("clientId", Long.parseLong(clientId));
    }

    return retAttr;
  }

  private void throwExceptionIfReqd(List<String> unsupportedParams) {
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
}
