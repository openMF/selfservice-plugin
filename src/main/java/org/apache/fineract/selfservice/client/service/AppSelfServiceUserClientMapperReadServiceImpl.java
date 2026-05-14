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
package org.apache.fineract.selfservice.client.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
@Slf4j
public class AppSelfServiceUserClientMapperReadServiceImpl
    implements AppSelfServiceUserClientMapperReadService {

  private final JdbcTemplate jdbcTemplate;
  private final PlatformSelfServiceSecurityContext context;

  @Override
  public Boolean isClientMappedToSelfServiceUser(Long clientId, Long appUserId) {
    return this.jdbcTemplate.queryForObject(
        "select case when (count(*) > 0) then true else false end from m_selfservice_user_client_mapping where client_id = ? and appuser_id = ?",
        Boolean.class,
        clientId,
        appUserId);
  }

  @Override
  public void validateAppSelfServiceUserClientsMapping(final Long clientId) {
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    if (clientId != null) {
      final boolean mappedClientId = isClientMappedToSelfServiceUser(clientId, user.getId());
      if (!mappedClientId) {
        throw new ClientNotFoundException(clientId);
      }
    } else {
      throw new ClientNotFoundException(clientId);
    }
  }
}
