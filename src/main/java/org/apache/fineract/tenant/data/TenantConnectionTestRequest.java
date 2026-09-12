/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

/**
 * A validated request to probe a database before a tenant is committed to the registry.
 *
 * <p>Carries a password because probing is the one operation that genuinely needs one; it is used
 * to open a connection and then discarded, never stored and never echoed back.
 *
 * @param schemaServer host to reach
 * @param schemaServerPort port to reach it on
 * @param schemaName database or schema to open
 * @param schemaUsername user to connect as
 * @param schemaPassword that user's password, used once and discarded
 * @param schemaConnectionParameters optional extra JDBC parameters
 */
public record TenantConnectionTestRequest(
    String schemaServer,
    String schemaServerPort,
    String schemaName,
    String schemaUsername,
    String schemaPassword,
    String schemaConnectionParameters) {}
