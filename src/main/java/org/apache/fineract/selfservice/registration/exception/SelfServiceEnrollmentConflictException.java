/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exception;

/** Raised when anonymous self-enrollment violates a uniqueness or other conflict constraint. */
public class SelfServiceEnrollmentConflictException extends RuntimeException {

  private final String parameterName;
  private final String userMessageGlobalisationCode;

  /**
   * Creates a conflict exception for a specific enrollment field.
   *
   * @param userMessageGlobalisationCode message code exposed to API clients for localization
   * @param defaultMessage fallback user-facing message
   * @param parameterName request field associated with the conflict
   */
  public SelfServiceEnrollmentConflictException(
      String userMessageGlobalisationCode, String defaultMessage, String parameterName) {
    super(defaultMessage);
    this.parameterName = parameterName;
    this.userMessageGlobalisationCode = userMessageGlobalisationCode;
  }

  /**
   * @return the request parameter associated with the conflict
   */
  public String getParameterName() {
    return parameterName;
  }

  /**
   * @return the globalization code describing the conflict
   */
  public String getUserMessageGlobalisationCode() {
    return userMessageGlobalisationCode;
  }
}
