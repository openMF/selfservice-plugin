/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
