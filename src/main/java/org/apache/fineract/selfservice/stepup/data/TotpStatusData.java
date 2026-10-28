/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.data;

/**
 * Where the authenticator of the caller stands. {@code status} is {@code NONE}, {@code PENDING}
 * (secret shown, not confirmed) or {@code ACTIVE}. {@code available} is {@code false} when the
 * tenant did not switch the feature on or has no usable key, so a client hides the section. {@code
 * exclusive}: while the authenticator is active, SMS and e-mail codes are not offered.
 */
public record TotpStatusData(
    boolean available,
    String status,
    int recoveryCodesRemaining,
    boolean exclusive,
    String confirmedAt) {}
