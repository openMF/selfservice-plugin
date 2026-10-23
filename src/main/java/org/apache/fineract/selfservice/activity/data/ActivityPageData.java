/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.data;

import java.util.List;

/** Fineract's page convention: the number of matching rows and the rows of this page. */
public record ActivityPageData(long totalFilteredRecords, List<ActivityData> pageItems) {}
