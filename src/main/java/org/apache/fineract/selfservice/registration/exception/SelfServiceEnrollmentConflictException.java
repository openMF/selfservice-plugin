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
 *
 * <p>For duplicate externalId (e.g. legal representative already registered as a person client
 * before the entity/company is enrolled), optional {@link #clientId} and {@link #externalId} let
 * the client continue entity registration by linking to the existing client.
 */
public class SelfServiceEnrollmentConflictException extends RuntimeException {

  private final String parameterName;
  private final String userMessageGlobalisationCode;
  private final Long userId;
  private final Boolean pendingConfirmation;
  private final OnboardingProgressData onboarding;
  private final Long clientId;
  private final String externalId;

  /**
   * Creates a conflict exception for a specific enrollment field (no onboarding payload).
   *
   * @param userMessageGlobalisationCode message code exposed to API clients for localization
   * @param defaultMessage fallback user-facing message
   * @param parameterName request field associated with the conflict
   */
  public SelfServiceEnrollmentConflictException(
      String userMessageGlobalisationCode, String defaultMessage, String parameterName) {
    this(userMessageGlobalisationCode, defaultMessage, parameterName, null, null, null, null, null);
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
    this(
        userMessageGlobalisationCode,
        defaultMessage,
        parameterName,
        userId,
        pendingConfirmation,
        onboarding,
        null,
        null);
  }

  /**
   * Full constructor including optional existing client identity (duplicate externalId / legal
   * representative already present).
   *
   * @param userMessageGlobalisationCode message code exposed to API clients for localization
   * @param defaultMessage fallback user-facing message
   * @param parameterName request field associated with the conflict
   * @param userId existing self-service user id, if resolved
   * @param pendingConfirmation {@code true} when the account is not yet enabled / confirmed
   * @param onboarding current onboarding progress for that user, if available
   * @param clientId existing Fineract client id linked to the conflicting externalId
   * @param externalId the conflicting external identifier value
   */
  public SelfServiceEnrollmentConflictException(
      String userMessageGlobalisationCode,
      String defaultMessage,
      String parameterName,
      Long userId,
      Boolean pendingConfirmation,
      OnboardingProgressData onboarding,
      Long clientId,
      String externalId) {
    super(defaultMessage);
    this.parameterName = parameterName;
    this.userMessageGlobalisationCode = userMessageGlobalisationCode;
    this.userId = userId;
    this.pendingConfirmation = pendingConfirmation;
    this.onboarding = onboarding;
    this.clientId = clientId;
    this.externalId = externalId;
  }

  /**
   * Factory for duplicate externalId when a legal representative (or other client) already exists.
   *
   * @param externalId the conflicting external identifier
   * @param clientId existing Fineract client id (may be null if lookup failed)
   */
  public static SelfServiceEnrollmentConflictException duplicateExternalId(
      String externalId, Long clientId) {
    String message =
        clientId != null
            ? "A client with externalId '"
                + externalId
                + "' already exists (clientId="
                + clientId
                + "). Use this clientId to continue entity registration with the legal representative."
            : "A client with externalId '" + externalId + "' already exists.";
    return new SelfServiceEnrollmentConflictException(
        "error.msg.client.duplicate.externalId",
        message,
        "externalId",
        null,
        null,
        null,
        clientId,
        externalId);
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

  /**
   * @return existing Fineract client id when the conflict is a duplicate externalId (e.g. legal
   *     representative already registered); otherwise null
   */
  public Long getClientId() {
    return clientId;
  }

  /** @return the conflicting external identifier, or null if not applicable */
  public String getExternalId() {
    return externalId;
  }
}