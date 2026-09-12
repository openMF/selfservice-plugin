/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * Thrown when a tenant would be bound to a database it must not use.
 *
 * <p>Creating a tenant reuses an existing database of the requested name, so the registry itself
 * has to decide whose data a database is. This covers the three refusals: the tenant store's own
 * database, a database another registered tenant already uses, and one retained from a removed
 * tenant under a different identifier.
 */
public class TenantSchemaUnavailableException extends AbstractPlatformDomainRuleException {

  private TenantSchemaUnavailableException(
      final String code, final String message, final Object... args) {
    super(code, message, args);
  }

  /** The requested database is the tenant store itself. */
  public static TenantSchemaUnavailableException tenantStore(final String schemaName) {
    return new TenantSchemaUnavailableException(
        "error.msg.tenant.schema.is.tenant.store",
        "Database " + schemaName + " is the tenant store and cannot hold a tenant",
        schemaName);
  }

  /** Another registered tenant already uses the requested database. */
  public static TenantSchemaUnavailableException inUse(
      final String schemaName, final String owner) {
    return new TenantSchemaUnavailableException(
        "error.msg.tenant.schema.in.use",
        "Database " + schemaName + " is already used by tenant " + owner,
        schemaName,
        owner);
  }

  /** The requested database was retained from a removed tenant with a different identifier. */
  public static TenantSchemaUnavailableException retained(
      final String schemaName, final String owner) {
    return new TenantSchemaUnavailableException(
        "error.msg.tenant.schema.retained",
        "Database " + schemaName + " holds the retained data of removed tenant " + owner,
        schemaName,
        owner);
  }
}
