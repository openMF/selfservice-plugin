/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

/**
 * Database connection behind a tenant, as returned to clients.
 *
 * <p>Deliberately carries no password field. {@code tenant_server_connections} stores both a
 * read-write and a read-only password, encrypted at rest by Fineract core, and neither is ever
 * serialised back to a caller: a credential that is written but never read cannot leak through the
 * API, and the administration UI has no use for the current value. Passwords are therefore
 * write-only, set through create and update and omitted here.
 *
 * @param id primary key in {@code tenant_server_connections}
 * @param schemaName database or schema holding the tenant's data
 * @param schemaServer host the schema lives on
 * @param schemaServerPort port the schema is reached on
 * @param schemaUsername user the platform connects as, never its password
 * @param schemaConnectionParameters extra JDBC parameters, may be null
 * @param autoUpdate whether the schema is migrated automatically on startup
 */
public record TenantConnectionData(
    Long id,
    String schemaName,
    String schemaServer,
    String schemaServerPort,
    String schemaUsername,
    String schemaConnectionParameters,
    boolean autoUpdate) {}
