/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

import java.util.List;

/**
 * Options an administration client needs to build the create and edit forms.
 *
 * <p>Served so the UI never hardcodes a list the backend owns, and so a status added later reaches
 * the UI without a frontend release.
 *
 * @param timezones selectable IANA zone identifiers
 * @param statuses selectable lifecycle states
 */
public record TenantTemplateData(List<String> timezones, List<String> statuses) {}
