/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.data;

/**
 * Response of challenge creation and resend. Timestamps are ISO-8601 local date-times in the tenant
 * time zone. It never contains the OTP.
 */
public record StepUpChallengeData(
    Long challengeId,
    String action,
    String channel,
    String maskedDestination,
    String expiresAt,
    int attemptsRemaining,
    String resendAvailableAt,
    int resendsRemaining) {}
