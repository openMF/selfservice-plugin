/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;

/** Thrown when no tenant in the central registry matches the requested id or identifier. */
public class TenantNotFoundException extends AbstractPlatformResourceNotFoundException {

  public TenantNotFoundException(final Long id) {
    super("error.msg.tenant.id.invalid", "Tenant with id " + id + " does not exist", id);
  }

  public TenantNotFoundException(final String identifier) {
    super(
        "error.msg.tenant.identifier.invalid",
        "Tenant with identifier " + identifier + " does not exist",
        identifier);
  }
}
