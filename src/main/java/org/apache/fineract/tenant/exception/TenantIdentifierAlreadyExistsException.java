/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.exception;

import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;

/**
 * Thrown when a tenant is created with an identifier already in the registry.
 *
 * <p>The identifier is how every request selects a tenant, so a duplicate would make routing
 * ambiguous. The database enforces this with a unique constraint; this exception exists so the
 * clash is reported as a clear validation failure rather than surfacing as a raw constraint
 * violation.
 */
public class TenantIdentifierAlreadyExistsException extends PlatformDataIntegrityException {

  public TenantIdentifierAlreadyExistsException(final String identifier) {
    super(
        "error.msg.tenant.identifier.already.exists",
        "A tenant with the identifier " + identifier + " already exists",
        "identifier",
        identifier);
  }
}
