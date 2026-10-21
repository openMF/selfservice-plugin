/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.selfservice.notification.NotificationContext;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Delivers the OTP through the existing self-service notification module ({@code
 * SelfServiceNotificationService}), so e-mail/SMS providers, SMTP fallback, the external gateway
 * and tenant propagation are all reused.
 *
 * <p>Channel selection reuses the notification routing rules: only the chosen contact is put on the
 * event (the other one is {@code null}) and {@code emailMode} is set accordingly, so the OTP goes
 * out on exactly that channel. The event is flagged {@code sensitive=true} so the notification
 * service does not also fan the code out to WhatsApp / in-app.
 *
 * <p>{@link NotificationCooldownCache} is only a de-duplication cache of the notification module;
 * it would silently swallow a legitimate resend, so its key is released before publishing. The real
 * resend limits are enforced from the database in {@link StepUpChallengeService}.
 */
abstract class NotificationStepUpDeliveryChannel implements StepUpDeliveryChannel {

  private final StepUpChannel channel;
  private final ApplicationEventPublisher eventPublisher;
  private final NotificationCooldownCache cooldownCache;

  NotificationStepUpDeliveryChannel(
      final StepUpChannel channel,
      final ApplicationEventPublisher eventPublisher,
      final NotificationCooldownCache cooldownCache) {
    this.channel = channel;
    this.eventPublisher = eventPublisher;
    this.cooldownCache = cooldownCache;
  }

  @Override
  public StepUpChannel channel() {
    return channel;
  }

  @Override
  public void deliver(
      final StepUpContact contact,
      final String otp,
      final StepUpAction action,
      final int ttlSeconds,
      final Locale locale,
      final String ipAddress) {
    final boolean email = channel == StepUpChannel.EMAIL;

    final Map<String, Object> contextData = new HashMap<>();
    contextData.put("authCode", otp);
    contextData.put("expirationMinutes", Math.max(1, (ttlSeconds + 59) / 60));
    contextData.put("actionLabel", StepUpMessages.actionLabel(action, locale));
    contextData.put("sensitive", Boolean.TRUE);

    final SelfServiceNotificationEvent event =
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.STEP_UP_OTP,
            contact.userId(),
            contact.firstName(),
            contact.lastName(),
            contact.username(),
            email ? contact.email() : null,
            email ? null : contact.mobile(),
            email,
            ipAddress,
            locale,
            contextData);

    cooldownCache.release(
        SelfServiceNotificationEvent.Type.STEP_UP_OTP.name() + ":" + contact.userId());
    try (NotificationContext.Scope ignored =
        NotificationContext.bind(SelfServiceNotificationEvent.Type.STEP_UP_OTP.name())) {
      eventPublisher.publishEvent(event);
    }
  }
}
