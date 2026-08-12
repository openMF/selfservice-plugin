/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;

/**
 * Raised when a password-renew operation fails because the supplied reset token is invalid,
 * already consumed, or expired.
 */
public class SelfServicePasswordResetTokenException extends AbstractPlatformDomainRuleException {

  public static SelfServicePasswordResetTokenException invalid() {
    return new SelfServicePasswordResetTokenException(
        SelfServiceApiConstants.ERROR_TOKEN_INVALID, "Invalid or expired reset token.");
  }

  public static SelfServicePasswordResetTokenException consumed() {
    return new SelfServicePasswordResetTokenException(
        SelfServiceApiConstants.ERROR_TOKEN_CONSUMED, "Reset token has already been used.");
  }

  public static SelfServicePasswordResetTokenException expired() {
    return new SelfServicePasswordResetTokenException(
        SelfServiceApiConstants.ERROR_TOKEN_EXPIRED,
        "Reset token has expired. Please request a new one.");
  }

  private SelfServicePasswordResetTokenException(
      final String globalisationMessageCode, final String defaultUserMessage) {
    super(globalisationMessageCode, defaultUserMessage);
  }
}