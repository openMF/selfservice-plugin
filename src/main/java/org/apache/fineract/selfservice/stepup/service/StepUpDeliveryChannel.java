/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.util.Locale;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;

/**
 * Delivers a freshly generated OTP to the user over one channel.
 *
 * <p>This is the extension point for new channels (an authenticator/TOTP "channel" would not
 * deliver anything but verify differently, and is planned as a follow-up).
 */
public interface StepUpDeliveryChannel {

  /** The channel this implementation serves. */
  StepUpChannel channel();

  /**
   * Hands the OTP to the delivery mechanism. Implementations must not log {@code otp} and must not
   * block the request thread on the provider (delivery is asynchronous and best effort: the caller
   * can always resend).
   */
  void deliver(
      StepUpContact contact,
      String otp,
      StepUpAction action,
      int ttlSeconds,
      Locale locale,
      String ipAddress);
}
