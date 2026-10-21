/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.SelfServiceTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

/**
 * A step-up OTP is flagged {@code sensitive} and must only go out on the e-mail/SMS channel that
 * was chosen: the external gateway must not also fan it out to WhatsApp or in-app. Regular events
 * keep their existing fan-out.
 */
@ExtendWith(MockitoExtension.class)
class SelfServiceNotificationServiceSensitiveEventTest {

  @Mock private SelfServiceTemplateService templateService;
  @Mock private SelfServicePluginEmailService emailService;
  @Mock private SmsMessageRepository smsMessageRepository;
  @Mock private SmsMessageScheduledJobService smsScheduledJobService;
  @Mock private SmsCampaignDropdownReadPlatformService smsProviderService;
  @Mock private NotificationCooldownCache cooldownCache;
  @Mock private Environment env;
  @Mock private ExternalNotificationSystemClient externalClient;

  private SelfServiceNotificationService service;

  @BeforeEach
  void setUp() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "timezone", null));
    final HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
    dates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    ThreadLocalContextUtil.setBusinessDates(dates);

    service =
        new SelfServiceNotificationService(
            templateService,
            emailService,
            smsMessageRepository,
            smsScheduledJobService,
            smsProviderService,
            cooldownCache,
            env,
            externalClient);

    when(env.getProperty(
            eq("fineract.selfservice.notification.enabled"), eq(Boolean.class), any(Boolean.class)))
        .thenReturn(true);
    when(env.getProperty(
            eq("fineract.selfservice.notification.step-up-otp.enabled"),
            eq(Boolean.class),
            any(Boolean.class)))
        .thenReturn(true);
    when(cooldownCache.tryAcquire(any())).thenReturn(true);

    final NotificationCredentialsData creds = new NotificationCredentialsData();
    creds.setEnabled(true);
    creds.setSms(true);
    creds.setWhatsapp(true);
    creds.setInApp(true);
    when(externalClient.resolveNotificationCredentials()).thenReturn(creds);
    when(templateService.mergeTemplate(any(), any(), any())).thenReturn("message");
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  private SelfServiceNotificationEvent smsEvent(final Map<String, Object> contextData) {
    return SelfServiceNotificationEvent.withTenantContext(
        this,
        SelfServiceNotificationEvent.Type.STEP_UP_OTP,
        1L,
        "Ana",
        "Perez",
        "ana",
        null,
        "5519500324",
        false,
        "127.0.0.1",
        Locale.US,
        contextData);
  }

  @Test
  void sensitiveEvent_goesOnlyOverSms_notWhatsappOrInApp() {
    service.handleNotification(smsEvent(Map.of("authCode", "123456", "sensitive", Boolean.TRUE)));

    verify(externalClient, times(1)).sendPostRequest(any());
    verify(templateService)
        .mergeTemplate(eq(SelfServiceNotificationEvent.Type.STEP_UP_OTP), eq("SMS"), any());
    verify(templateService, never())
        .mergeTemplate(eq(SelfServiceNotificationEvent.Type.STEP_UP_OTP), eq("WHATSAPP"), any());
    verify(templateService, never())
        .mergeTemplate(eq(SelfServiceNotificationEvent.Type.STEP_UP_OTP), eq("IN_APP"), any());
  }

  @Test
  void regularEvent_stillFansOutToWhatsappAndInApp() {
    service.handleNotification(smsEvent(Map.of("authCode", "123456")));

    // SMS + WhatsApp + in-app through the external gateway.
    verify(externalClient, times(3)).sendPostRequest(any());
    verify(templateService)
        .mergeTemplate(eq(SelfServiceNotificationEvent.Type.STEP_UP_OTP), eq("WHATSAPP"), any());
    verify(templateService)
        .mergeTemplate(eq(SelfServiceNotificationEvent.Type.STEP_UP_OTP), eq("IN_APP"), any());
  }

  @Test
  void eventWithoutContextData_isNotConsideredSensitive() {
    service.handleNotification(smsEvent(null));

    verify(externalClient, times(3)).sendPostRequest(any());
  }
}
