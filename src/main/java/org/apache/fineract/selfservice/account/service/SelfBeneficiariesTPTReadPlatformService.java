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

import java.util.Collection;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.data.SelfBeneficiariesTPTData;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;

public interface SelfBeneficiariesTPTReadPlatformService {

  Collection<SelfBeneficiariesTPTData> retrieveAll();

  Collection<SelfAccountTemplateData> retrieveTPTSelfAccountTemplateData(AppSelfServiceUser user);

  Long getTransferLimit(Long id, Long accountId, Integer accountType);

  // Añadido para la validación en la fase de Prepare
  boolean isBeneficiaryRegistered(Long appUserId, String accountNumber);
}
