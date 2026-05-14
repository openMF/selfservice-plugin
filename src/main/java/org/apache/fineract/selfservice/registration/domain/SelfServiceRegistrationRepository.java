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
package org.apache.fineract.selfservice.registration.domain;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SelfServiceRegistrationRepository
    extends JpaRepository<SelfServiceRegistration, Long>,
        JpaSpecificationExecutor<SelfServiceRegistration> {

  String FIND_BY_REQUEST_AND_AUTHENTICATION_TOKEN =
      "select request from SelfServiceRegistration request where request.id = :id and "
          + "request.authenticationToken = :authenticationToken";

  String FIND_BY_REQUEST_TYPE_AND_AUTHENTICATION_TOKEN =
      "select request from SelfServiceRegistration request where request.id = :id and "
          + "request.authenticationToken = :authenticationToken and request.requestType = :requestType";

  String FIND_BY_EXTERNAL_AUTHORIZATION_TOKEN =
      "select request from SelfServiceRegistration request where request.externalAuthorizationToken = :externalAuthorizationToken and "
          + "request.requestType = :requestType";

  /**
   * Finds a self-service request by its identifier and authentication token.
   *
   * @param id request identifier
   * @param authenticationToken authentication token stored with the request
   * @return matching request, or {@code null} when no record matches
   */
  @Query(FIND_BY_REQUEST_AND_AUTHENTICATION_TOKEN)
  SelfServiceRegistration getRequestByIdAndAuthenticationToken(
      @Param("id") Long id, @Param("authenticationToken") String authenticationToken);

  /**
   * Finds a self-service request by identifier, authentication token, and request type.
   *
   * @param id request identifier
   * @param authenticationToken authentication token stored with the request
   * @param requestType expected request type
   * @return matching request, or {@code null} when no record matches
   */
  @Query(FIND_BY_REQUEST_TYPE_AND_AUTHENTICATION_TOKEN)
  SelfServiceRegistration getRequestByIdAndAuthenticationToken(
      @Param("id") Long id,
      @Param("authenticationToken") String authenticationToken,
      @Param("requestType") SelfServiceRequestType requestType);

  /**
   * Finds a self-service request by external authorization token and request type.
   *
   * @param externalAuthorizationToken external authorization token stored with the request
   * @param requestType expected request type
   * @return matching request, or {@code null} when no record matches
   */
  @Query(FIND_BY_EXTERNAL_AUTHORIZATION_TOKEN)
  SelfServiceRegistration getRequestByExternalAuthorizationToken(
      @Param("externalAuthorizationToken") String externalAuthorizationToken,
      @Param("requestType") SelfServiceRequestType requestType);

  /**
   * Deletes all self-service registration requests whose expiry timestamp is strictly before the
   * supplied cutoff.
   *
   * <p>This covers all request types (REGISTRATION, ENROLLMENT, PASSWORD_RESET) regardless of their
   * consumed status — an expired token is unusable whether or not it was consumed.
   *
   * @param cutoff reference timestamp; rows with {@code expiresAt < cutoff} are deleted
   * @return number of rows deleted
   */
  @Modifying
  @Transactional
  @Query(
      "DELETE FROM SelfServiceRegistration r WHERE r.expiresAt IS NOT NULL AND r.expiresAt < :cutoff")
  int deleteExpiredRequests(@Param("cutoff") LocalDateTime cutoff);
}
