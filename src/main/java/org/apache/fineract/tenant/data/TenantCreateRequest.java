/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

import org.apache.fineract.tenant.domain.TenantStatus;

/**
 * A validated request to create a tenant.
 *
 * <p>Produced only by {@code TenantManagementDataValidator}; every field has already been checked
 * by the time one of these exists, so the write service does not re-validate.
 *
 * @param identifier unique key clients will send as {@code X-Mifos-Platform-TenantId}
 * @param name human readable name
 * @param timezoneId IANA zone identifier
 * @param status initial lifecycle state, never null - defaulted to ACTIVE when not supplied
 * @param description optional free text
 * @param contactEmail optional administrative contact
 * @param schemaName database or schema to provision and connect to
 * @param schemaServer host the schema lives on
 * @param schemaServerPort port the schema is reached on
 * @param schemaUsername user the platform connects as
 * @param schemaPassword password for that user, write-only and never returned
 * @param schemaConnectionParameters optional extra JDBC parameters
 * @param autoUpdate whether the schema is migrated automatically on startup
 */
public record TenantCreateRequest(
    String identifier,
    String name,
    String timezoneId,
    TenantStatus status,
    String description,
    String contactEmail,
    String schemaName,
    String schemaServer,
    String schemaServerPort,
    String schemaUsername,
    String schemaPassword,
    String schemaConnectionParameters,
    boolean autoUpdate) {}
