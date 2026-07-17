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

import java.util.Collection;

/**
 * Immutable view of the accounts linked to a pocket, grouped by account type for presentation to
 * self-service clients.
 */
public final class PocketAccountMappingData {

  private final Collection<PocketAccountMappingDto> loanAccounts;
  private final Collection<PocketAccountMappingDto> savingsAccounts;
  private final Collection<PocketAccountMappingDto> shareAccounts;

  private PocketAccountMappingData(
      final Collection<PocketAccountMappingDto> loanAccounts,
      final Collection<PocketAccountMappingDto> savingsAccounts,
      final Collection<PocketAccountMappingDto> shareAccounts) {
    this.loanAccounts = loanAccounts;
    this.savingsAccounts = savingsAccounts;
    this.shareAccounts = shareAccounts;
  }

  public static PocketAccountMappingData instance(
      final Collection<PocketAccountMappingDto> loanAccounts,
      final Collection<PocketAccountMappingDto> savingsAccounts,
      final Collection<PocketAccountMappingDto> shareAccounts) {
    return new PocketAccountMappingData(loanAccounts, savingsAccounts, shareAccounts);
  }

  public Collection<PocketAccountMappingDto> getLoanAccounts() {
    return this.loanAccounts;
  }

  public Collection<PocketAccountMappingDto> getSavingsAccounts() {
    return this.savingsAccounts;
  }

  public Collection<PocketAccountMappingDto> getShareAccounts() {
    return this.shareAccounts;
  }
}
