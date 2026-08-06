/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;

public interface SelfServiceRegistrationWritePlatformService {

  SelfServiceRegistration createRegistrationRequest(String apiRequestBodyAsJson);

  AppSelfServiceUser createSelfServiceUser(String apiRequestBodyAsJson, HttpServletRequest httpRequest);

  AppSelfServiceUser createSelfServiceUserOrEnroll(String apiRequestBodyAsJson, HttpServletRequest httpRequest);

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
  SelfServiceRegistration selfEnroll(String apiRequestBodyAsJson, HttpServletRequest httpRequest);

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
