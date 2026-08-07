/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exception;

import org.apache.fineract.onboarding.domain.OnboardingProgressData;

/**
 * Raised when anonymous self-enrollment violates a uniqueness or other conflict constraint.
 *
 * <p>For duplicate username, optional {@link #userId}, {@link #pendingConfirmation} and {@link
 * #onboarding} let the client resume enrollment without a separate progress call.
 */
public class SelfServiceEnrollmentConflictException extends RuntimeException {

  private final String parameterName;
  private final String userMessageGlobalisationCode;
  private final Long userId;
  private final Boolean pendingConfirmation;
  private final OnboardingProgressData onboarding;

  /**
   * Creates a conflict exception for a specific enrollment field (no onboarding payload).
   *
   * @param userMessageGlobalisationCode message code exposed to API clients for localization
   * @param defaultMessage fallback user-facing message
   * @param parameterName request field associated with the conflict
   */
  public SelfServiceEnrollmentConflictException(
      String userMessageGlobalisationCode, String defaultMessage, String parameterName) {
    this(userMessageGlobalisationCode, defaultMessage, parameterName, null, null, null);
  }

  /**
   * Creates a conflict exception, optionally carrying existing-user onboarding progress (e.g.
   * duplicate username).
   *
   * @param userMessageGlobalisationCode message code exposed to API clients for localization
   * @param defaultMessage fallback user-facing message
   * @param parameterName request field associated with the conflict
   * @param userId existing self-service user id, if resolved
   * @param pendingConfirmation {@code true} when the account is not yet enabled / confirmed
   * @param onboarding current onboarding progress for that user, if available
   */
  public SelfServiceEnrollmentConflictException(
      String userMessageGlobalisationCode,
      String defaultMessage,
      String parameterName,
      Long userId,
      Boolean pendingConfirmation,
      OnboardingProgressData onboarding) {
    super(defaultMessage);
    this.parameterName = parameterName;
    this.userMessageGlobalisationCode = userMessageGlobalisationCode;
    this.userId = userId;
    this.pendingConfirmation = pendingConfirmation;
    this.onboarding = onboarding;
  }

  /** @return the request parameter associated with the conflict */
  public String getParameterName() {
    return parameterName;
  }

  /** @return the globalization code describing the conflict */
  public String getUserMessageGlobalisationCode() {
    return userMessageGlobalisationCode;
  }

  /** @return existing self-service user id when resolved for duplicate username; otherwise null */
  public Long getUserId() {
    return userId;
  }

  /**
   * @return {@code true} if the existing account is pending confirmation (disabled); {@code null}
   *     if unknown
   */
  public Boolean getPendingConfirmation() {
    return pendingConfirmation;
  }

  /** @return onboarding progress for the existing user, or null if not loaded */
  public OnboardingProgressData getOnboarding() {
    return onboarding;
  }
}