/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

/**
 * How a step-up code reaches, or is produced for, the user: SMS and EMAIL send a code; TOTP is the
 * code of the user's authenticator app, nothing is sent.
 */
public enum StepUpChannel {
  SMS,
  EMAIL,
  TOTP
}
