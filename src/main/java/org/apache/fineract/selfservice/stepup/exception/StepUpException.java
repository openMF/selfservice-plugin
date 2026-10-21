/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;

/**
 * Domain error of the step-up module. Carries a machine-readable {@code
 * error.msg.selfservice.stepup.*} code, the HTTP status to answer with and a few non-sensitive
 * details (action, attempts remaining, retry-after) that the {@code StepUpExceptionMapper} exposes
 * in the response.
 *
 * <p>Messages are deliberately generic: they never reveal the OTP, tokens, digests, or whether a
 * challenge exists for somebody else.
 */
public class StepUpException extends RuntimeException {

  public static final String CODE_PREFIX = "error.msg.selfservice.stepup.";

  private final String errorCode;
  private final int httpStatus;
  private final transient Map<String, Object> details;

  private StepUpException(
      final String suffix,
      final int httpStatus,
      final String message,
      final Map<String, Object> details) {
    super(message);
    this.errorCode = CODE_PREFIX + suffix;
    this.httpStatus = httpStatus;
    this.details = details;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  /** Extra non-sensitive fields to expose next to the error (insertion ordered). */
  public Map<String, Object> getDetails() {
    return details;
  }

  private static Map<String, Object> details(final Object... keyValues) {
    final Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  // 403 -----------------------------------------------------------------------------------------

  /** The protected operation was called without a step-up token. */
  public static StepUpException required(final StepUpAction action) {
    return new StepUpException(
        "required",
        403,
        "Step-up authentication is required for this operation.",
        details("action", action.name()));
  }

  /** Unknown, expired, already used, or not bound to this user/session/action/payload. */
  public static StepUpException invalid() {
    return new StepUpException(
        "invalid", 403, "The step-up verification is invalid or has expired.", details());
  }

  /** The request is not tied to a login session (e.g. HTTP Basic with user:password). */
  public static StepUpException sessionRequired() {
    return new StepUpException(
        "session.required",
        403,
        "Step-up authentication requires an authenticated session token.",
        details());
  }

  // 400 -----------------------------------------------------------------------------------------

  public static StepUpException otpInvalid(final int attemptsRemaining) {
    return new StepUpException(
        "otp.invalid",
        400,
        "The verification code is incorrect.",
        details("attemptsRemaining", attemptsRemaining));
  }

  public static StepUpException otpExpired() {
    return new StepUpException(
        "otp.expired", 400, "The verification code has expired. Request a new one.", details());
  }

  public static StepUpException disabled() {
    return new StepUpException(
        "disabled", 400, "Step-up authentication is not enabled.", details());
  }

  public static StepUpException actionNotProtected(final StepUpAction action) {
    return new StepUpException(
        "action.not.protected",
        400,
        "This action does not require step-up authentication.",
        details("action", action.name()));
  }

  public static StepUpException channelUnavailable() {
    return new StepUpException(
        "channel.unavailable",
        400,
        "The selected delivery channel is not available for this user.",
        details());
  }

  /** The legacy in-line transfer OTP is replaced by step-up while step-up protects transfers. */
  public static StepUpException legacyOtpDisabled() {
    return new StepUpException(
        "legacy.otp.disabled",
        400,
        "The transfer OTP is handled by step-up authentication. Use /v1/self/stepup/challenges.",
        details());
  }

  // 429 -----------------------------------------------------------------------------------------

  public static StepUpException locked(final long retryAfterSeconds) {
    return new StepUpException(
        "locked",
        429,
        "Too many incorrect attempts. Try again later.",
        details("retryAfterSeconds", Math.max(1L, retryAfterSeconds)));
  }

  public static StepUpException resendCooldown(final long retryAfterSeconds) {
    return new StepUpException(
        "resend.cooldown",
        429,
        "A code was sent recently. Wait before requesting another.",
        details("retryAfterSeconds", Math.max(1L, retryAfterSeconds)));
  }

  public static StepUpException resendLimitReached() {
    return new StepUpException(
        "resend.limit",
        429,
        "The maximum number of resends was reached. Start a new verification.",
        details());
  }

  public static StepUpException rateLimited(final long retryAfterSeconds) {
    return new StepUpException(
        "rate.limited",
        429,
        "Too many verification requests. Try again later.",
        details("retryAfterSeconds", Math.max(1L, retryAfterSeconds)));
  }

  // 500 -----------------------------------------------------------------------------------------

  /** Step-up is enabled but cannot work safely (e.g. no HMAC secret). Fails closed. */
  public static StepUpException misconfigured() {
    return new StepUpException(
        "misconfigured",
        500,
        "Step-up authentication is not configured correctly. Contact the administrator.",
        details());
  }
}
