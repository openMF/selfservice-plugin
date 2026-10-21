/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.event;

import java.time.LocalDateTime;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;

/**
 * Audit event published for every step-up state change so that the activity feed (US-E12-05) or any
 * other consumer can subscribe with a plain {@code @EventListener}.
 *
 * <p>The event never carries an OTP, token, digest or session value. {@code reason} is a short
 * internal code (e.g. {@code payload.mismatch}) meant for operators, it is never returned to API
 * clients.
 */
public record StepUpEvent(
    Type type,
    Long challengeId,
    Long userId,
    StepUpAction action,
    StepUpChannel channel,
    String reason,
    LocalDateTime occurredAt,
    String ipAddress) {

  public enum Type {
    ISSUED,
    RESENT,
    VERIFIED,
    FAILED,
    LOCKED,
    CONSUMED,
    REJECTED
  }
}
