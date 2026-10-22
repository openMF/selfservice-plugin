/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.util.Locale;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class NotificationStepUpDeliveryChannelTest {

  @Mock private ApplicationEventPublisher publisher;
  @Mock private NotificationCooldownCache cooldownCache;

  private final StepUpContact contact =
      new StepUpContact(9L, "ana", "Ana", "Perez", "ana@example.com", "5519500324");

  private EmailStepUpDeliveryChannel email;
  private SmsStepUpDeliveryChannel sms;

  @BeforeEach
  void setUp() {
    email = new EmailStepUpDeliveryChannel(publisher, cooldownCache);
    sms = new SmsStepUpDeliveryChannel(publisher, cooldownCache);
  }

  private SelfServiceNotificationEvent captureEvent() {
    final ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(publisher).publishEvent(captor.capture());
    return (SelfServiceNotificationEvent) captor.getValue();
  }

  @Test
  void channelsReportTheirType() {
    assertEquals(StepUpChannel.EMAIL, email.channel());
    assertEquals(StepUpChannel.SMS, sms.channel());
  }

  @Test
  void email_putsOnlyTheEmailOnTheEvent_inEmailMode() {
    email.deliver(contact, "123456", StepUpAction.TPT_TRANSFER, 300, Locale.US, "10.0.0.1");

    final SelfServiceNotificationEvent event = captureEvent();
    assertEquals(SelfServiceNotificationEvent.Type.STEP_UP_OTP, event.getType());
    assertEquals("ana@example.com", event.getEmail());
    assertNull(event.getMobileNumber(), "the other contact must not be on the event");
    assertTrue(event.isEmailMode());
    assertEquals(9L, event.getUserId());
  }

  @Test
  void sms_putsOnlyTheMobileOnTheEvent_inSmsMode() {
    sms.deliver(contact, "123456", StepUpAction.TPT_TRANSFER, 300, Locale.US, "10.0.0.1");

    final SelfServiceNotificationEvent event = captureEvent();
    assertEquals("5519500324", event.getMobileNumber());
    assertNull(event.getEmail());
    assertFalse(event.isEmailMode());
  }

  @Test
  void event_carriesTemplateDataAndIsFlaggedSensitive() {
    sms.deliver(
        contact, "042917", StepUpAction.CHANGE_PASSWORD, 300, Locale.forLanguageTag("es-MX"), null);

    final SelfServiceNotificationEvent event = captureEvent();
    assertEquals("042917", event.getContextData().get("authCode"));
    assertEquals(5, event.getContextData().get("expirationMinutes"));
    assertEquals("cambio de contraseña", event.getContextData().get("actionLabel"));
    assertEquals(Boolean.TRUE, event.getContextData().get("sensitive"));
    assertEquals(Locale.forLanguageTag("es-MX"), event.getLocale());
  }

  @Test
  void expirationMinutes_roundsUp_andActionLabelFollowsTheLocale() {
    email.deliver(contact, "123456", StepUpAction.TPT_TRANSFER, 90, Locale.ENGLISH, null);

    final SelfServiceNotificationEvent event = captureEvent();
    assertEquals(2, event.getContextData().get("expirationMinutes"));
    assertEquals("third-party transfer", event.getContextData().get("actionLabel"));
  }

  @Test
  void cooldownKeyIsReleasedBeforePublishing_soAResendIsNeverSwallowed() {
    email.deliver(contact, "123456", StepUpAction.TPT_TRANSFER, 300, Locale.US, null);

    final InOrder order = inOrder(cooldownCache, publisher);
    order.verify(cooldownCache).release("STEP_UP_OTP:9");
    order.verify(publisher).publishEvent(org.mockito.ArgumentMatchers.any(ApplicationEvent.class));
  }

  @Test
  void eventToString_neverExposesTheOtp() {
    sms.deliver(contact, "424242", StepUpAction.TPT_TRANSFER, 300, Locale.US, null);

    assertFalse(captureEvent().toString().contains("424242"));
  }
}
