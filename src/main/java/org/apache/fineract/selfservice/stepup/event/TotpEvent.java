/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.event;

import java.time.LocalDateTime;

/**
 * Audit event of the authenticator app (pairing, removal, recovery codes, lock-out), published like
 * {@link StepUpEvent} so that the activity history and any other consumer can subscribe with a
 * plain {@code @EventListener}.
 *
 * <p>It never carries a code, a secret or a hash. {@code reason} is a short internal code meant for
 * operators.
 */
public record TotpEvent(
    Type type, Long userId, String reason, LocalDateTime occurredAt, String ipAddress) {

  public enum Type {
    /** The authenticator was paired (the first code of the app was accepted). */
    ENROLLED,
    /** The user removed the authenticator. */
    REMOVED,
    /** A staff member removed the authenticator (lost phone). */
    RESET_BY_STAFF,
    /** A new set of recovery codes replaced the old one. */
    RECOVERY_CODES_REGENERATED,
    /** A recovery code was accepted (and is gone). */
    RECOVERY_CODE_USED,
    /** Too many wrong codes: the authenticator is locked for a while. */
    LOCKED
  }
}
