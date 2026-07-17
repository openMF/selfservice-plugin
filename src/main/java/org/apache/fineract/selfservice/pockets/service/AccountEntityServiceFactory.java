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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves the {@link AccountEntityService} for a given account type key (e.g. {@code LOAN}). */
public class AccountEntityServiceFactory {

  private final Map<String, AccountEntityService> accountEntityServiceHashMap = new HashMap<>();

  public AccountEntityServiceFactory(final Set<AccountEntityService> accountEntityServices) {
    for (AccountEntityService service : accountEntityServices) {
      this.accountEntityServiceHashMap.put(service.getKey(), service);
    }
  }

  public Optional<AccountEntityService> getAccountEntityService(final String key) {
    return Optional.ofNullable(this.accountEntityServiceHashMap.get(key));
  }
}
