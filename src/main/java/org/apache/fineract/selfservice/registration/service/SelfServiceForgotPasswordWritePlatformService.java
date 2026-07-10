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

/**
 * Service responsible for handling self-service password reset workflows, including request
 * creation (OTP/token generation) and password renewal.
 */
public interface SelfServiceForgotPasswordWritePlatformService {

  /**
   * Creates a password reset request for the user identified by the {@code username} in the
   * provided JSON payload. A reset token is generated and delivered through the notification
   * channels enabled in the system.
   *
   * @param apiRequestBodyAsJson JSON payload containing at least the {@code username} field
   * @return the created registration request, or {@code null} if the request could not be created
   */
  SelfServiceRegistration createForgotPasswordRequest(String apiRequestBodyAsJson);

  /**
   * Renews the user's password using a valid, non-expired reset token.
   *
   * @param apiRequestBodyAsJson JSON payload containing the reset token and the new password
   * @return the command processing result with the renewed user's ID
   */
  CommandProcessingResult renewPassword(String apiRequestBodyAsJson);
}