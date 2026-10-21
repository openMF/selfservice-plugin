/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.data;

import java.util.List;

/**
 * What a client needs to know up-front to drive the step-up UI without a failed first attempt:
 * whether step-up is on, which actions are protected, which channels this user can actually use,
 * and the timing/length parameters.
 */
public record StepUpConfigData(
    boolean enabled,
    List<String> protectedActions,
    List<String> availableChannels,
    int otpLength,
    int otpTtlSeconds,
    int maxAttempts,
    int resendCooldownSeconds,
    int maxResends,
    int grantTtlSeconds,
    boolean sessionBound) {}
