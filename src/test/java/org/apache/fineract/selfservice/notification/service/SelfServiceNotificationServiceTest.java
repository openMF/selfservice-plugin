/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.campaigns.sms.data.SmsProviderData;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.EmailDetail;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.core.service.SmtpConfigurationUnavailableException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;
import org.apache.fineract.infrastructure.sms.domain.SmsMessage;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.thymeleaf.ITemplateEngine;

@ExtendWith(MockitoExtension.class)
class SelfServiceNotificationServiceTest {

  @Mock private ITemplateEngine templateEngine;
  @Mock private MessageSource messageSource;
  @Mock private SelfServicePluginEmailService emailService;
  @Mock private SmsMessageRepository smsMessageRepository;
  @Mock private SmsMessageScheduledJobService smsScheduledJobService;
  @Mock private SmsCampaignDropdownReadPlatformService smsProviderService;
  @Mock private NotificationCooldownCache cooldownCache;
  @Mock private Environment env;
  @Mock private ExternalNotificationSystemClient externalNotificationSystemClient;

  private SelfServiceNotificationService service;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger logger;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "timezone", null));
    HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
    dates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    ThreadLocalContextUtil.setBusinessDates(dates);

    service =
        new SelfServiceNotificationService(
            templateEngine,
            messageSource,
            emailService,
            smsMessageRepository,
            smsScheduledJobService,
            smsProviderService,
            cooldownCache,
            env,
            externalNotificationSystemClient);

    logger = (Logger) LoggerFactory.getLogger(SelfServiceNotificationService.class);
    originalLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil.reset();
    logger.detachAppender(logAppender);
    logger.setLevel(originalLevel);
    logAppender.stop();
  }

  // ---- Helper to build a standard email-mode event ----

  private SelfServiceNotificationEvent emailEvent() {
    return new SelfServiceNotificationEvent(
        this,
        SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
        1L,
        "Test",
        "User",
        "testuser",
        "test@example.com",
        "1234567890",
        true,
        "127.0.0.1",
        Locale.US);
  }

  private SelfServiceNotificationEvent smsEvent() {
    return new SelfServiceNotificationEvent(
        this,
        SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
        1L,
        "Test",
        "User",
        "testuser",
        "test@example.com",
        "1234567890",
        false,
        "127.0.0.1",
        Locale.US);
  }

  private void stubNotificationEnabled() {
    when(env.getProperty(
            eq("fineract.selfservice.notification.enabled"), eq(Boolean.class), any(Boolean.class)))
        .thenReturn(true);
    when(env.getProperty(
            eq("fineract.selfservice.notification.login-success.enabled"),
            eq(Boolean.class),
            any(Boolean.class)))
        .thenReturn(true);
  }

  private void stubExternalNotificationDisabled() {
    NotificationCredentialsData creds = new NotificationCredentialsData();
    creds.setEnabled(false);
    when(externalNotificationSystemClient.resolveNotificationCredentials()).thenReturn(creds);
  }

  // ---- Email happy path ----

  @Test
  void handleNotification_EmailMode_SendsEmail() {
    stubNotificationEnabled();
    stubExternalNotificationDisabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);
    when(templateEngine.process(eq("html/login-success"), any())).thenReturn("<html>body</html>");
    when(messageSource.getMessage(
            eq("subject.login-success"), eq(null), eq("subject.login-success"), any(Locale.class)))
        .thenReturn("New Login");

    service.handleNotification(emailEvent());

    verify(emailService).sendFormattedEmail(any(EmailDetail.class));
    verify(smsMessageRepository, never()).save(any());
  }

  // ---- SMS happy path ----

  @Test
  void handleNotification_SmsMode_SendsSms() {
    stubNotificationEnabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);

    SmsProviderData provider = mock(SmsProviderData.class);
    when(provider.getId()).thenReturn(5L);
    when(smsProviderService.retrieveSmsProviders()).thenReturn(Collections.singletonList(provider));
    when(templateEngine.process(eq("text/login-success"), any())).thenReturn("Hello text body");
    when(smsMessageRepository.save(any(SmsMessage.class))).thenAnswer(i -> i.getArgument(0));

    service.handleNotification(smsEvent());

    verify(smsMessageRepository, times(2)).save(any(SmsMessage.class));
    verify(smsScheduledJobService).sendTriggeredMessage(any(), eq(5L));
  }

  @Test
  void handleNotification_SmsMode_SkipsIfNoProvider() {
    stubNotificationEnabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);
    when(smsProviderService.retrieveSmsProviders()).thenReturn(Collections.emptyList());

    service.handleNotification(smsEvent());

    verify(smsProviderService).retrieveSmsProviders();
    verify(smsMessageRepository, never()).save(any());
  }

  // ---- Feature flag tests ----

  @Test
  void handleNotification_GlobalDisabled_SkipsAll() {
    when(env.getProperty(
            eq("fineract.selfservice.notification.enabled"), eq(Boolean.class), any(Boolean.class)))
        .thenReturn(false);

    service.handleNotification(emailEvent());

    verify(cooldownCache, never()).tryAcquire(any());
    verify(emailService, never()).sendFormattedEmail(any());
  }

  @Test
  void handleNotification_EventDisabled_SkipsEvent() {
    when(env.getProperty(
            eq("fineract.selfservice.notification.enabled"), eq(Boolean.class), any(Boolean.class)))
        .thenReturn(true);
    when(env.getProperty(
            eq("fineract.selfservice.notification.login-success.enabled"),
            eq(Boolean.class),
            any(Boolean.class)))
        .thenReturn(false);

    service.handleNotification(emailEvent());

    verify(cooldownCache, never()).tryAcquire(any());
    verify(emailService, never()).sendFormattedEmail(any());
  }

  // ---- Cooldown tests ----

  @Test
  void handleNotification_CooldownPreventsRepeat() {
    stubNotificationEnabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(false);

    service.handleNotification(emailEvent());

    verify(emailService, never()).sendFormattedEmail(any());
    verify(templateEngine, never()).process(any(String.class), any());
  }

  // ---- Blank email skip ----

  @Test
  void handleNotification_EmailMode_SkipsWhenBlankEmail() {
    stubNotificationEnabled();
    stubExternalNotificationDisabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);
    when(messageSource.getMessage(
            eq("subject.login-success"), eq(null), eq("subject.login-success"), any(Locale.class)))
        .thenReturn("Subject");

    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "testuser",
            "  ",
            "1234567890",
            true,
            "127.0.0.1",
            Locale.US);

    service.handleNotification(event);

    verify(emailService, never()).sendFormattedEmail(any());

    List<ILoggingEvent> warns =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .collect(Collectors.toList());
    assertTrue(
        warns.stream().anyMatch(e -> e.getFormattedMessage().contains("no email address")),
        "Should log warning about missing email");
  }

  // ---- SMTP config error handling ----

  @Test
  void handleNotification_EmailMode_ReleaseCooldownOnConfigError() {
    stubNotificationEnabled();
    stubExternalNotificationDisabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);
    when(templateEngine.process(eq("html/login-success"), any())).thenReturn("<html>body</html>");
    when(messageSource.getMessage(
            eq("subject.login-success"), eq(null), eq("subject.login-success"), any(Locale.class)))
        .thenReturn("Subject");

    doThrow(
            new org.apache.fineract.infrastructure.core.service.PlatformEmailSendException(
                new SmtpConfigurationUnavailableException("SMTP not configured")))
        .when(emailService)
        .sendFormattedEmail(any(EmailDetail.class));

    service.handleNotification(emailEvent());

    // Cooldown MUST be released so the event can be retried after config fix
    verify(cooldownCache).release("LOGIN_SUCCESS:1");
  }

  @Test
  void handleNotification_EmailMode_LogsWarnOnFirstConfigError_ThenDebug() {
    stubNotificationEnabled();
    stubExternalNotificationDisabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);
    when(templateEngine.process(eq("html/login-success"), any())).thenReturn("<html>body</html>");
    when(messageSource.getMessage(
            eq("subject.login-success"), eq(null), eq("subject.login-success"), any(Locale.class)))
        .thenReturn("Subject");

    doThrow(
            new org.apache.fineract.infrastructure.core.service.PlatformEmailSendException(
                new SmtpConfigurationUnavailableException("SMTP not configured")))
        .when(emailService)
        .sendFormattedEmail(any(EmailDetail.class));

    // First call — should log WARN
    service.handleNotification(emailEvent());
    // Second call — should log DEBUG
    service.handleNotification(emailEvent());

    List<ILoggingEvent> smtpLogs =
        logAppender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("SMTP configuration unavailable"))
            .collect(Collectors.toList());

    assertEquals(2, smtpLogs.size(), "Expected 2 SMTP config log events");
    assertEquals(Level.WARN, smtpLogs.get(0).getLevel(), "First config error should be WARN");
    assertTrue(
        smtpLogs.get(0).getFormattedMessage().contains("Further config errors"),
        "First WARN should mention suppression of future errors");
    assertEquals(
        Level.DEBUG, smtpLogs.get(1).getLevel(), "Subsequent config errors should be DEBUG");
  }

  // ---- General exception handling ----

  @Test
  void handleNotification_EmailMode_ReleaseCooldownOnGeneralError() {
    stubNotificationEnabled();
    stubExternalNotificationDisabled();
    when(cooldownCache.tryAcquire(any())).thenReturn(true);
    when(templateEngine.process(eq("html/login-success"), any())).thenReturn("<html>body</html>");
    when(messageSource.getMessage(
            eq("subject.login-success"), eq(null), eq("subject.login-success"), any(Locale.class)))
        .thenReturn("Subject");

    doThrow(new RuntimeException("Unexpected failure"))
        .when(emailService)
        .sendFormattedEmail(any(EmailDetail.class));

    service.handleNotification(emailEvent());

    // Cooldown MUST be released on general errors too
    verify(cooldownCache).release("LOGIN_SUCCESS:1");

    List<ILoggingEvent> errors =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .collect(Collectors.toList());
    assertTrue(
        errors.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("Failed to handle notification")),
        "Should log ERROR for general exceptions");
  }

  // ---- Tenant context restoration tests ----

  @Test
  void handleNotification_RestoresTenantFromEvent_WhenThreadHasNoTenant() {
    // Simulate the afterCommit scenario: thread has NO tenant, but event carries one
    ThreadLocalContextUtil.reset();

    FineractPlatformTenant eventTenant =
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null);
    HashMap<BusinessDateType, LocalDate> eventDates = new HashMap<>();
    eventDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 15));

    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "testuser",
            "test@example.com",
            "1234567890",
            true,
            "127.0.0.1",
            Locale.US,
            eventTenant,
            eventDates);

    // Notifications disabled so handleNotification returns early after restoring context
    when(env.getProperty(
            eq("fineract.selfservice.notification.enabled"), eq(Boolean.class), any(Boolean.class)))
        .thenReturn(false);

    service.handleNotification(event);

    // The finally block in handleNotification() correctly resets ThreadLocal to prevent
    // tenant context leakage in the thread pool, so we verify the restore-then-cleanup
    // lifecycle via log output instead of post-call ThreadLocal state.

    // 1. Verify tenant was restored from the event during execution
    List<ILoggingEvent> restoredLogs =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.DEBUG)
            .filter(
                e ->
                    e.getFormattedMessage()
                        .contains("Restored tenant 'default' from notification event"))
            .collect(Collectors.toList());
    assertEquals(1, restoredLogs.size(), "Should log that tenant was restored from event payload");

    // 2. Verify cleanup happened (prevents thread pool context leakage)
    List<ILoggingEvent> resetLogs =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.DEBUG)
            .filter(e -> e.getFormattedMessage().contains("Reset tenant context"))
            .collect(Collectors.toList());
    assertEquals(1, resetLogs.size(), "Should log that tenant context was reset after processing");
  }

  @Test
  void handleNotification_LogsWarning_WhenNoTenantAvailable() {
    // No tenant in event, no tenant on thread
    ThreadLocalContextUtil.reset();

    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "testuser",
            "test@example.com",
            "1234567890",
            true,
            "127.0.0.1",
            Locale.US);
    // event.getTenant() == null, event.getBusinessDates() == null

    when(env.getProperty(
            eq("fineract.selfservice.notification.enabled"), eq(Boolean.class), any(Boolean.class)))
        .thenReturn(false);

    service.handleNotification(event);

    List<ILoggingEvent> warns =
        logAppender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("No tenant context available"))
            .collect(Collectors.toList());
    assertEquals(1, warns.size(), "Should log WARN when no tenant is available from any source");
  }

  @Test
  void withTenantContext_CapturesCurrentTenantFromThreadLocal() {
    // Set tenant on current thread
    FineractPlatformTenant tenant =
        new FineractPlatformTenant(2L, "test-tenant", "Test", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);
    HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
    dates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 1));
    ThreadLocalContextUtil.setBusinessDates(dates);

    SelfServiceNotificationEvent event =
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "testuser",
            "test@example.com",
            "1234567890",
            true,
            "127.0.0.1",
            Locale.US);

    assertEquals(
        "test-tenant",
        event.getTenant().getTenantIdentifier(),
        "Factory should capture tenant from ThreadLocal");
    assertEquals(
        LocalDate.of(2026, 1, 1),
        event.getBusinessDates().get(BusinessDateType.BUSINESS_DATE),
        "Factory should capture business dates from ThreadLocal");
  }

  @Test
  void withTenantContext_HandlesNoTenantGracefully() {
    ThreadLocalContextUtil.reset();

    SelfServiceNotificationEvent event =
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "testuser",
            "test@example.com",
            "1234567890",
            true,
            "127.0.0.1",
            Locale.US);

    // Should not throw, tenant/dates should be null
    assertTrue(event.getTenant() == null, "Tenant should be null when ThreadLocal is empty");
    assertTrue(
        event.getBusinessDates() == null,
        "Business dates should be null when ThreadLocal is empty");
  }
}
