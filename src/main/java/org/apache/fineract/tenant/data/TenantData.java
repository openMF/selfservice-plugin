/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.apache.fineract.tenant.domain.TenantStatus;

/**
 * A tenant as returned to clients.
 *
 * @param id primary key in {@code tenants}
 * @param identifier unique key clients send as {@code X-Mifos-Platform-TenantId}, immutable once
 *     created because it is how every request selects a tenant
 * @param name human readable name
 * @param timezoneId IANA zone the tenant's business dates are interpreted in
 * @param status lifecycle state, or null when the stored value is not one this plugin recognises -
 *     such a tenant is refused by the status filter until its status is set again
 * @param description optional free text, may be null
 * @param contactEmail optional administrative contact, may be null
 * @param joinedDate date the tenant joined, may be null on rows predating the field
 * @param createdDate when the row was created, may be null on rows predating the field
 * @param lastModifiedDate when the row was last changed, may be null
 * @param connection the tenant's read-write connection, without credentials
 */
public record TenantData(
    Long id,
    String identifier,
    String name,
    String timezoneId,
    TenantStatus status,
    String description,
    String contactEmail,
    LocalDate joinedDate,
    OffsetDateTime createdDate,
    OffsetDateTime lastModifiedDate,
    TenantConnectionData connection) {}
