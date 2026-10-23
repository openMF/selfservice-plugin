/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.data;

import java.util.Map;

/**
 * One entry of the activity feed as the API returns it. {@code occurredAt} is the tenant clock (ISO
 * local date-time) and {@code ipAddress} is already masked. {@code type} and {@code outcome} are
 * codes that the client translates; {@code details} only holds whitelisted keys.
 */
public record ActivityData(
    Long id,
    String type,
    String outcome,
    String occurredAt,
    String ipAddress,
    Map<String, Object> details) {}
