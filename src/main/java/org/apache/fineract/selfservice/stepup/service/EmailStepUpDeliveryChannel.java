/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** OTP delivery by e-mail. */
@Component
public class EmailStepUpDeliveryChannel extends NotificationStepUpDeliveryChannel {

  public EmailStepUpDeliveryChannel(
      final ApplicationEventPublisher eventPublisher,
      final NotificationCooldownCache cooldownCache) {
    super(StepUpChannel.EMAIL, eventPublisher, cooldownCache);
  }
}
