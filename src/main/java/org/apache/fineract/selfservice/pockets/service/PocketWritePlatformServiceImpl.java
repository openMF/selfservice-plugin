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
package org.apache.fineract.selfservice.pockets.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.selfservice.pockets.api.PocketApiConstants;
import org.apache.fineract.selfservice.pockets.data.PocketDataValidator;
import org.apache.fineract.selfservice.pockets.domain.Pocket;
import org.apache.fineract.selfservice.pockets.domain.PocketAccountMapping;
import org.apache.fineract.selfservice.pockets.domain.PocketAccountMappingRepositoryWrapper;
import org.apache.fineract.selfservice.pockets.domain.PocketRepositoryWrapper;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links accounts to, and delinks accounts from, the authenticated self-service user's pocket. A
 * pocket is lazily created the first time the user links an account.
 */
@RequiredArgsConstructor
public class PocketWritePlatformServiceImpl implements PocketWritePlatformService {

  private final PlatformSelfServiceSecurityContext context;
  private final PocketDataValidator pocketDataValidator;
  private final AccountEntityServiceFactory accountEntityServiceFactory;
  private final PocketRepositoryWrapper pocketRepositoryWrapper;
  private final PocketAccountMappingRepositoryWrapper pocketAccountMappingRepositoryWrapper;
  private final PocketAccountMappingReadPlatformService pocketAccountMappingReadPlatformService;

  @Transactional
  @Override
  public CommandProcessingResult linkAccounts(JsonCommand command) {

    this.pocketDataValidator.validateForLinkingAccounts(command.json());
    JsonArray accountsDetail = command.arrayOfParameterNamed(PocketApiConstants.accountsDetail);

    Long pocketId =
        this.pocketRepositoryWrapper.findByAppUserId(
            this.context.authenticatedSelfServiceUser().getId());

    if (pocketId == null) {
      final Pocket pocket = Pocket.instance(this.context.authenticatedSelfServiceUser().getId());
      this.pocketRepositoryWrapper.saveAndFlush(pocket);
      pocketId = pocket.getId();
    }

    final List<PocketAccountMapping> pocketAccounts = new ArrayList<>();

    for (int i = 0; i < accountsDetail.size(); i++) {
      final JsonObject element = accountsDetail.get(i).getAsJsonObject();
      final Long accountId = element.get(PocketApiConstants.accountIdParamName).getAsLong();
      final String accountType = element.get(PocketApiConstants.accountTypeParamName).getAsString();

      final AccountEntityService accountEntityService =
          this.accountEntityServiceFactory
              .getAccountEntityService(accountType.toUpperCase())
              .orElseThrow(
                  () ->
                      new PlatformDataIntegrityException(
                          "error.msg.pocket.account.type.not.supported",
                          "Account type `" + accountType + "` is not supported for pockets.",
                          PocketApiConstants.accountTypeParamName,
                          accountType));
      accountEntityService.validateSelfUserAccountMapping(accountId);
      Integer accountTypeValue = EntityAccountType.valueOf(accountType.toUpperCase()).getValue();
      if (this.pocketAccountMappingReadPlatformService.validatePocketAndAccountMapping(
          pocketId, accountId, accountTypeValue)) {
        throw new PlatformDataIntegrityException(
            PocketApiConstants.duplicateMappingException,
            PocketApiConstants.duplicateMappingExceptionMessage,
            accountId,
            accountType);
      }

      final String accountNumber = accountEntityService.retrieveAccountNumberByAccountId(accountId);

      pocketAccounts.add(
          PocketAccountMapping.instance(pocketId, accountId, accountTypeValue, accountNumber));
    }
    this.pocketAccountMappingRepositoryWrapper.save(pocketAccounts);
    return new CommandProcessingResultBuilder()
        .withCommandId(command.commandId())
        .withEntityId(pocketId)
        .build();
  }

  @Transactional
  @Override
  public CommandProcessingResult delinkAccounts(JsonCommand command) {
    this.pocketDataValidator.validateForDeLinkingAccounts(command.json());
    JsonArray pocketAccountMappingList =
        command.arrayOfParameterNamed(PocketApiConstants.pocketAccountMappingList);

    Long pocketId =
        this.pocketRepositoryWrapper.findByAppUserIdWithNotFoundDetection(
            this.context.authenticatedSelfServiceUser().getId());

    final List<PocketAccountMapping> pocketAccounts = new ArrayList<>();

    for (JsonElement mapping : pocketAccountMappingList) {

      final Long mappingId = mapping.getAsLong();

      PocketAccountMapping pocketAccountMapping =
          this.pocketAccountMappingRepositoryWrapper.findByIdAndPocketIdWithNotFoundException(
              mappingId, pocketId);

      if (pocketAccountMapping != null) {
        pocketAccounts.add(pocketAccountMapping);
      }
    }

    this.pocketAccountMappingRepositoryWrapper.delete(pocketAccounts);
    return new CommandProcessingResultBuilder()
        .withCommandId(command.commandId())
        .withEntityId(pocketId)
        .build();
  }
}
