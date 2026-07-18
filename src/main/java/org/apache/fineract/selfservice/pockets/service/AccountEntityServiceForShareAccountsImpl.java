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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.portfolio.accounts.exceptions.ShareAccountNotFoundException;
import org.apache.fineract.portfolio.shareaccounts.service.ShareAccountReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.shareaccounts.service.AppUserShareAccountsMapperReadPlatformService;

/** {@link AccountEntityService} implementation for share accounts. */
@RequiredArgsConstructor
public class AccountEntityServiceForShareAccountsImpl implements AccountEntityService {

  private static final String KEY = EntityAccountType.SHARES.name();

  private final PlatformSelfServiceSecurityContext context;
  private final AppUserShareAccountsMapperReadPlatformService
      appUserShareAccountsMapperReadPlatformService;
  private final ShareAccountReadPlatformService shareAccountReadPlatformService;

  @Override
  public String getKey() {
    return KEY;
  }

  @Override
  public void validateSelfUserAccountMapping(Long accountId) {
    if (!this.appUserShareAccountsMapperReadPlatformService.isShareAccountsMappedToUser(
        accountId, this.context.authenticatedSelfServiceUser().getId())) {
      throw new ShareAccountNotFoundException(accountId);
    }
  }

  @Override
  public String retrieveAccountNumberByAccountId(Long accountId) {
    return this.shareAccountReadPlatformService.retrieveAccountNumberByAccountId(accountId);
  }
}
