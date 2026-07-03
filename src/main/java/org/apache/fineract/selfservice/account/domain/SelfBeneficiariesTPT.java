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
package org.apache.fineract.selfservice.account.domain;

import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.NAME_PARAM_NAME;
import static org.apache.fineract.selfservice.account.api.SelfBeneficiariesTPTApiConstants.TRANSFER_LIMIT_PARAM_NAME;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(
    name = "m_selfservice_beneficiaries_tpt",
    uniqueConstraints = {
      @UniqueConstraint(
          columnNames = {"name", "app_selfservice_user_id", "is_active"},
          name = "name")
    })
public class SelfBeneficiariesTPT extends AbstractPersistableCustom<Long> {

  @Column(name = "app_selfservice_user_id", nullable = false)
  private Long appSelfServiceUserId;

  @Column(name = "name", length = 50, nullable = false)
  private String name;

  @Column(name = "office_id", nullable = false)
  private Long officeId;

  @Column(name = "client_id", nullable = false)
  private Long clientId;

  @Column(name = "account_id", nullable = false)
  private Long accountId;

  @Column(name = "account_type", nullable = false)
  private Integer accountType;

  @Column(name = "transfer_limit", nullable = true)
  private Long transferLimit;

  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;

  // Campos pra informacion beneficiario de PIN y SIMPE
  @Column(
      name = "custom_account_number",
      length = 50,
      nullable = true) // Almacena el IBAN o el número de Teléfono
  private String customAccountNumber;

  @Column(
      name = "holder_name",
      length = 150,
      nullable = true) // Dueño de la cuenta (holder / destinationCustomerName)
  private String holderName;

  @Column(
      name = "holder_id",
      length = 30,
      nullable = true) // Cédula del dueño (holderId / destinationCustomerId)
  private String holderId;

  @Column(
      name = "holder_id_type",
      nullable = true) // Tipo de cédula (holderIdType / destinationIdType)
  private Integer holderIdType;

  @Column(name = "currency_code", length = 3, nullable = true) // "CRC" o "USD"
  private String currencyCode;

  @Column(
      name = "entity_code",
      length = 10,
      nullable = true) // Código del banco (ej: "0151", "0373")
  private String entityCode;

  @Column(name = "entity_name", length = 150, nullable = true) // Nombre del banco / financiera
  private String entityName;

  protected SelfBeneficiariesTPT() {
    //
  }

  public SelfBeneficiariesTPT(
      Long appSelfServiceUserId,
      String name,
      Long officeId,
      Long clientId,
      Long accountId,
      Integer accountType,
      Long transferLimit) {
    this.appSelfServiceUserId = appSelfServiceUserId;
    this.name = name;
    this.officeId = officeId;
    this.clientId = clientId;
    this.accountId = accountId;
    this.accountType = accountType;
    this.transferLimit = transferLimit;
  }

  public String getName() {
    return this.name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Long getTransferLimit() {
    return this.transferLimit;
  }

  public void setTransferLimit(Long transferLimit) {
    this.transferLimit = transferLimit;
  }

  public boolean isActive() {
    return this.isActive;
  }

  public void setActive(boolean isActive) {
    this.isActive = isActive;
  }

  public Long getAppSelfServiceUserId() {
    return this.appSelfServiceUserId;
  }

  public Long getOfficeId() {
    return this.officeId;
  }

  public Long getClientId() {
    return this.clientId;
  }

  public Long getAccountId() {
    return this.accountId;
  }

  public Integer getAccountType() {
    return this.accountType;
  }

  public void setCustomAccountNumber(String customAccountNumber) {
    this.customAccountNumber = customAccountNumber;
  }

  public void setHolderName(String holderName) {
    this.holderName = holderName;
  }

  public void setHolderId(String holderId) {
    this.holderId = holderId;
  }

  public void setHolderIdType(Integer holderIdType) {
    this.holderIdType = holderIdType;
  }

  public void setCurrencyCode(String currencyCode) {
    this.currencyCode = currencyCode;
  }

  public void setEntityCode(String entityCode) {
    this.entityCode = entityCode;
  }

  public void setEntityName(String entityName) {
    this.entityName = entityName;
  }

  public String getCustomAccountNumber() {
    return this.customAccountNumber;
  }

  public String getHolderName() {
    return this.holderName;
  }

  public String getHolderId() {
    return this.holderId;
  }

  public Integer getHolderIdType() {
    return this.holderIdType;
  }

  public String getCurrencyCode() {
    return this.currencyCode;
  }

  public String getEntityCode() {
    return this.entityCode;
  }

  public String getEntityName() {
    return this.entityName;
  }

  public Map<String, Object> update(String newName, Long newTransferLimit) {
    Map<String, Object> changes = new HashMap<>();
    if (!this.name.equals(newName)) {
      this.name = newName;
      changes.put(NAME_PARAM_NAME, newName);
    }
    if ((this.transferLimit != null && !this.transferLimit.equals(newTransferLimit))
        || (this.transferLimit == null && newTransferLimit != null)) {
      this.transferLimit = newTransferLimit;
      changes.put(TRANSFER_LIMIT_PARAM_NAME, newTransferLimit);
    }
    return changes;
  }
}