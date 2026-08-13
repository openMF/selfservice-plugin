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
package org.apache.fineract.selfservice.useradministration.domain;

import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AppSelfServiceUserClientMappingRepository
    extends JpaRepository<AppSelfServiceUserClientMapping, Long> {

  @Modifying(clearAutomatically = true)
  @Transactional
  @CacheEvict(
      value = {
        "appSelfServiceUserClientFetchByClientId",
        "appSelfServiceUserUserClientFetchByAppuserUsername",
        "appSelfServiceUserUserClientFetchByAppUserId"
      },
      allEntries = true)
  @Query(
      value =
          "INSERT INTO m_selfservice_user_client_mapping (appuser_id, client_id) VALUES (?1, ?2)",
      nativeQuery = true)
  void saveClientUserMapping(@Param("appuserId") Long appuserId, @Param("clientId") Long clientId);

  /**
   * Lookup mapping by Fineract client id (backoffice / webhook flows). Cache key is tenant-scoped
   * for multi-tenant safety.
   *
   * @return mapping or {@code null} if none exists
   */
  @Query(
      "SELECT appUserMapping FROM AppSelfServiceUserClientMapping appUserMapping"
          + " WHERE appUserMapping.client.id = :clientId")
  @Cacheable(
      value = "appSelfServiceUserClientFetchByClientId",
      key =
          "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil)"
              + ".getTenant().getTenantIdentifier().concat(#clientId)")
  AppSelfServiceUserClientMapping fetchByClientId(@Param("clientId") Long clientId);

  /**
   * Same as {@link #fetchByClientId(Long)} but as {@link Optional} for safer null handling in
   * onboarding / KYC backoffice APIs.
   */
  @Query(
      "SELECT appUserMapping FROM AppSelfServiceUserClientMapping appUserMapping"
          + " WHERE appUserMapping.client.id = :clientId")
  Optional<AppSelfServiceUserClientMapping> findByClientId(@Param("clientId") Long clientId);

  @Query(
      "SELECT appUserMapping FROM AppSelfServiceUserClientMapping appUserMapping"
          + " WHERE appUserMapping.appUser.username = :username")
  @Cacheable(
      value = "appSelfServiceUserUserClientFetchByAppuserUsername",
      key =
          "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil)"
              + ".getTenant().getTenantIdentifier().concat(#username)")
  AppSelfServiceUserClientMapping fetchByAppuserUsername(@Param("username") String username);

  @Query(
      "SELECT appUserMapping FROM AppSelfServiceUserClientMapping appUserMapping"
          + " WHERE appUserMapping.client.externalId = :externalId"
          + " AND appUserMapping.appUser.email = :email")
  Optional<AppSelfServiceUserClientMapping> fetchByClientExternalIdAndUserEmail(
      @Param("externalId") String externalId, @Param("email") String email);

  @Query(
      "SELECT appUserMapping FROM AppSelfServiceUserClientMapping appUserMapping"
          + " WHERE appUserMapping.appUser.id = :appUserId")
  @Cacheable(
      value = "appSelfServiceUserUserClientFetchByAppUserId",
      key =
          "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil)"
              + ".getTenant().getTenantIdentifier().concat(#appUserId)")
  AppSelfServiceUserClientMapping fetchByAppUserId(@Param("appUserId") Long appUserId);

  /** Optional variant of {@link #fetchByAppUserId(Long)}. */
  @Query(
      "SELECT appUserMapping FROM AppSelfServiceUserClientMapping appUserMapping"
          + " WHERE appUserMapping.appUser.id = :appUserId")
  Optional<AppSelfServiceUserClientMapping> findByAppUserId(@Param("appUserId") Long appUserId);

  boolean existsByAppUserIdAndClientId(Long appUserId, Long clientId);

  boolean existsByClientId(Long clientId);

  @Modifying(clearAutomatically = true)
  @Transactional
  @CacheEvict(
      value = {
        "appSelfServiceUserClientFetchByClientId",
        "appSelfServiceUserUserClientFetchByAppuserUsername",
        "appSelfServiceUserUserClientFetchByAppUserId"
      },
      allEntries = true)
  void deleteByAppUserIdAndClientId(Long appUserId, Long clientId);
}
