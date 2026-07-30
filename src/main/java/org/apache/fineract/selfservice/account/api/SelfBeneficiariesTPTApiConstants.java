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
package org.apache.fineract.selfservice.account.api;

public interface SelfBeneficiariesTPTApiConstants {

  String BENEFICIARY_ENTITY_NAME = "SSBENEFICIARYTPT";
  String RESOURCE_NAME = "beneficiary";
  String LOCALE = "locale";
  String NAME_PARAM_NAME = "name";
  String OFFICE_NAME_PARAM_NAME = "officeName";
  String ACCOUNT_TYPE_PARAM_NAME = "accountType";
  String ACCOUNT_NUMBER_PARAM_NAME = "accountNumber";
  String TRANSFER_LIMIT_PARAM_NAME = "transferLimit";

  String ID_PARAM_NAME = "id";
  String CLIENT_NAME_PARAM_NAME = "clientName";
  String ACCOUNT_TYPE_OPTIONS_PARAM_NAME = "accountTypeOptions";

  String DESTINATION_IBAN_PARAM_NAME = "destinationIban";
  String PHONE_NUMBER_PARAM_NAME = "phoneNumber";
  String HOLDER_PARAM_NAME = "holder";
  String HOLDER_ID_PARAM_NAME = "holderId";
  String HOLDER_ID_TYPE_PARAM_NAME = "holderIdType";

  String DESTINATION_CUSTOMER_NAME_PARAM_NAME = "destinationCustomerName";
  String DESTINATION_CUSTOMER_ID_PARAM_NAME = "destinationCustomerId";
  String DESTINATION_ID_TYPE_PARAM_NAME = "destinationIdType";

  String CURRENCY_CODE_PARAM_NAME = "currencyCode";
  String ENTITY_CODE_PARAM_NAME = "entityCode";
  String ENTITY_NAME_PARAM_NAME = "entityName";
  
  String PAYMENT_TYPE_PARAM_NAME = "paymentType";
  String CURRENCY_PARAM_NAME = "currency";
}
