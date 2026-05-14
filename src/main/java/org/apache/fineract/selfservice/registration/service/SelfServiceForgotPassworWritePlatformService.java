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

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;

public interface SelfServiceForgotPassworWritePlatformService {

  SelfServiceRegistration createForgotPasswordRequest(String apiRequestBodyAsJson);

  /**
   * Renews a self-service password using either the legacy {@code requestId + authenticationToken}
   * pair or the newer {@code externalAuthenticationToken}.
   *
   * <p>The payload must include {@code password} and {@code repeatPassword}; the token must be
   * valid, unexpired, and unused. The returned {@link CommandProcessingResult} identifies the
   * updated self-service user. Validation errors, missing identifiers, expired tokens, and invalid
   * tokens are reported as platform exceptions.
   *
   * @param apiRequestBodyAsJson request payload containing token fields and the new password
   * @return command result for the updated self-service user
   */
  CommandProcessingResult renewPassword(String apiRequestBodyAsJson);
}
