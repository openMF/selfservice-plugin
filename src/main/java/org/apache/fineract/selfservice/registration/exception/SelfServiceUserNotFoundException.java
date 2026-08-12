/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;

/**
 * Thrown when a self-service password-reset request is made for a username that does not exist
 * in the current tenant.
 *
 * <p>Mapped by Fineract's exception handling to HTTP 404.
 */
public class SelfServiceUserNotFoundException extends AbstractPlatformResourceNotFoundException {

  public SelfServiceUserNotFoundException(final String username) {
    super(
        "error.msg.self.service.user.not.found",
        "Self service user with username `" + username + "` does not exist",
        username);
  }
}