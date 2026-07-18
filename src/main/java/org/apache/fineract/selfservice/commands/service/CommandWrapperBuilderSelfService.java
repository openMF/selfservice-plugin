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
package org.apache.fineract.selfservice.commands.service;

import java.util.Set;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.core.domain.ExternalId;

public class CommandWrapperBuilderSelfService {

  private Long officeId;
  private Long groupId;
  private Long clientId;
  private Long loanId;
  private Long savingsId;
  private String actionName;
  private String entityName;
  private Long entityId;
  private Long subentityId;
  private String href;
  private String json = "{}";
  private String transactionId;
  private Long productId;
  private Long templateId;
  private Long creditBureauId;
  private Long organisationCreditBureauId;
  private String jobName;
  private String idempotencyKey;
  private ExternalId loanExternalId;
  private Set<String> sanitizeJsonKeys;

  public CommandWrapper build() {
    return new CommandWrapper(
        this.officeId,
        this.groupId,
        this.clientId,
        this.loanId,
        this.savingsId,
        this.actionName,
        this.entityName,
        this.entityId,
        this.subentityId,
        this.href,
        this.json,
        this.transactionId,
        this.productId,
        this.templateId,
        this.creditBureauId,
        this.organisationCreditBureauId,
        this.jobName,
        this.idempotencyKey,
        this.loanExternalId,
        this.sanitizeJsonKeys);
  }

  public CommandWrapper build(String idempotencyKey) {
    return new CommandWrapper(
        this.officeId,
        this.groupId,
        this.clientId,
        this.loanId,
        this.savingsId,
        this.actionName,
        this.entityName,
        this.entityId,
        this.subentityId,
        this.href,
        this.json,
        this.transactionId,
        this.productId,
        this.templateId,
        this.creditBureauId,
        this.organisationCreditBureauId,
        this.jobName,
        idempotencyKey,
        this.loanExternalId,
        this.sanitizeJsonKeys);
  }

  public CommandWrapperBuilderSelfService addSelfServiceBeneficiaryTPT() {
    this.actionName = "CREATE";
    this.entityName = "SSBENEFICIARYTPT";
    this.entityId = null;
    this.href = "/self/beneficiaries/tpt";
    return this;
  }

  public CommandWrapperBuilderSelfService updateSelfServiceBeneficiaryTPT(
      final Long beneficiaryId) {
    this.actionName = "UPDATE";
    this.entityName = "SSBENEFICIARYTPT";
    this.entityId = beneficiaryId;
    this.href = "/self/beneficiaries/tpt/" + beneficiaryId;
    return this;
  }

  public CommandWrapperBuilderSelfService deleteSelfServiceBeneficiaryTPT(
      final Long beneficiaryId) {
    this.actionName = "DELETE";
    this.entityName = "SSBENEFICIARYTPT";
    this.entityId = beneficiaryId;
    this.href = "/self/beneficiaries/tpt/" + beneficiaryId;
    return this;
  }

  public CommandWrapperBuilderSelfService linkAccountsToPocket() {
    this.actionName = "LINK_ACCOUNT_TO";
    this.entityName = "POCKET";
    this.entityId = null;
    this.href = "/self/pockets";
    return this;
  }

  public CommandWrapperBuilderSelfService delinkAccountsFromPocket() {
    this.actionName = "DELINK_ACCOUNT_FROM";
    this.entityName = "POCKET";
    this.entityId = null;
    this.href = "/self/pockets";
    return this;
  }

  public CommandWrapperBuilderSelfService withLoanId(final Long withLoanId) {
    this.loanId = withLoanId;
    return this;
  }

  public CommandWrapperBuilderSelfService withSavingsId(final Long withSavingsId) {
    this.savingsId = withSavingsId;
    return this;
  }

  public CommandWrapperBuilderSelfService withClientId(final Long withClientId) {
    this.clientId = withClientId;
    return this;
  }

  public CommandWrapperBuilderSelfService withGroupId(final Long withGroupId) {
    this.groupId = withGroupId;
    return this;
  }

  public CommandWrapperBuilderSelfService withEntityName(final String withEntityName) {
    this.entityName = withEntityName;
    return this;
  }

  public CommandWrapperBuilderSelfService withSubEntityId(final Long withSubEntityId) {
    this.subentityId = withSubEntityId;
    return this;
  }

  public CommandWrapperBuilderSelfService withJson(final String withJson) {
    this.json = withJson;
    return this;
  }

  public CommandWrapperBuilderSelfService withNoJsonBody() {
    this.json = null;
    return this;
  }
}
