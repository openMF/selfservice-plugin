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

import org.apache.fineract.selfservice.pockets.domain.PocketAccountMapping;

/**
 * API response view of a single pocket-account mapping. Decouples the serialized response from the
 * {@link PocketAccountMapping} persistence entity.
 */
public final class PocketAccountMappingDto {

  private final Long id;
  private final Long pocketId;
  private final Long accountId;
  private final Integer accountType;
  private final String accountNumber;

  private PocketAccountMappingDto(
      final Long id,
      final Long pocketId,
      final Long accountId,
      final Integer accountType,
      final String accountNumber) {
    this.id = id;
    this.pocketId = pocketId;
    this.accountId = accountId;
    this.accountType = accountType;
    this.accountNumber = accountNumber;
  }

  public static PocketAccountMappingDto from(final PocketAccountMapping mapping) {
    return new PocketAccountMappingDto(
        mapping.getId(),
        mapping.getPocketId(),
        mapping.getAccountId(),
        mapping.getAccountType(),
        mapping.getAccountNumber());
  }

  public Long getId() {
    return this.id;
  }

  public Long getPocketId() {
    return this.pocketId;
  }

  public Long getAccountId() {
    return this.accountId;
  }

  public Integer getAccountType() {
    return this.accountType;
  }

  public String getAccountNumber() {
    return this.accountNumber;
  }
}
