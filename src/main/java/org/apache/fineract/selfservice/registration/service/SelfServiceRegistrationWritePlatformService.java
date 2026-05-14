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
package org.apache.fineract.selfservice.registration.service;

import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;

public interface SelfServiceRegistrationWritePlatformService {

  SelfServiceRegistration createRegistrationRequest(String apiRequestBodyAsJson);

  AppSelfServiceUser createSelfServiceUser(String apiRequestBodyAsJson);

  AppSelfServiceUser createSelfServiceUserOrEnroll(String apiRequestBodyAsJson);

  /**
   * Creates a pending self-enrollment request from a JSON payload containing the registration
   * fields.
   *
   * <p>The payload is expected to include values such as {@code username}, {@code password}, {@code
   * firstName}, {@code lastName}, {@code authenticationMode}, and the client details needed to
   * create or link the self-service user.
   *
   * @param apiRequestBodyAsJson enrollment request JSON
   * @return the persisted {@link SelfServiceRegistration} awaiting confirmation
   * @throws org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException on
   *     invalid payload fields
   * @throws org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException on
   *     duplicate or persistence conflicts
   */
  SelfServiceRegistration selfEnroll(String apiRequestBodyAsJson);

  /**
   * Confirms a pending self-enrollment request and activates the associated self-service user.
   *
   * <p>The payload is expected to contain either {@code externalAuthenticationToken} or the legacy
   * {@code requestId}/{@code authenticationToken} combination used to resolve the pending request.
   *
   * @param apiRequestBodyAsJson confirmation request JSON
   * @return the activated {@link AppSelfServiceUser}
   * @throws org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException on
   *     invalid token payloads
   * @throws org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException when
   *     the token is invalid, expired, or already used
   */
  AppSelfServiceUser confirmEnrollment(String apiRequestBodyAsJson);
}
