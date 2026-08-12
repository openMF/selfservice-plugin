/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;

/**
 * Raised when the external identity system reports that the given national ID / externalId has no
 * identity information (or returns an explicit error payload).
 *
 * <p>Mapped by Fineract to HTTP 404.
 */
public class SelfServiceExternalIdentityNotFoundException
    extends AbstractPlatformResourceNotFoundException {

  public static final String GLOBALISATION_CODE =
      "error.msg.self.service.external.identity.not.found";

  /**
   * @param externalId the national ID / externalId that was queried
   * @param externalDescription message returned by the external system (e.g. {@code descripcion})
   */
  public SelfServiceExternalIdentityNotFoundException(
      final String externalId, final String externalDescription) {
    super(
        GLOBALISATION_CODE,
        externalDescription != null && !externalDescription.isBlank()
            ? externalDescription
            : "No identity information found for externalId `" + externalId + "`",
        externalId);
  }

  public SelfServiceExternalIdentityNotFoundException(final String externalId) {
    this(externalId, null);
  }
}