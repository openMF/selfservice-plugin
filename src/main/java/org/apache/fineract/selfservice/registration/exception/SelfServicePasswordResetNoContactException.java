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
 * Raised when a password-reset request cannot be fulfilled because the self-service user has
 * neither an email address nor a mobile number.
 */
public class SelfServicePasswordResetNoContactException
    extends AbstractPlatformDomainRuleException {

  public SelfServicePasswordResetNoContactException(final String username) {
    super(
        SelfServiceApiConstants.ERROR_NO_CONTACT_CHANNEL,
        "User `"
            + username
            + "` has no email or mobile number configured for password-reset delivery.",
        username);
  }
}