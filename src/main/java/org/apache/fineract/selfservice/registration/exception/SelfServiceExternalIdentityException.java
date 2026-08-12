/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * Raised when the external identity system is unreachable, returns a non-success HTTP status, or
 * returns a payload that cannot be interpreted.
 *
 * <p>Mapped by Fineract to HTTP 403.
 */
public class SelfServiceExternalIdentityException extends AbstractPlatformDomainRuleException {

  public static final String GLOBALISATION_CODE =
      "error.msg.self.service.external.identity.failed";

  public SelfServiceExternalIdentityException(final String externalId, final String detail) {
    super(
        GLOBALISATION_CODE,
        "External identity lookup failed for externalId `"
            + externalId
            + "`"
            + (detail != null && !detail.isBlank() ? ": " + detail : ""),
        externalId);
  }
}