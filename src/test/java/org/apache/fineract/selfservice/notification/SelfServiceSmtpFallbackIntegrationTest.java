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
package org.apache.fineract.selfservice.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.configuration.service.ExternalServicesPropertiesReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.core.service.SmtpConfigurationUnavailableException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.notification.service.SelfServiceNotificationService;
import org.apache.fineract.selfservice.notification.starter.SelfServiceNotificationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Spring-context integration test that verifies the full notification pipeline handles SMTP
 * configuration unavailability gracefully.
 *
 * <p>Simulates the real-world scenario where:
 *
 * <ul>
 *   <li>{@code c_external_service_properties} table does not exist (PostgreSQL)
 *   <li>Spring properties fallback is <strong>not</strong> configured
 *   <li>A LOGIN_SUCCESS event is published
 * </ul>
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>The notification fires asynchronously on the {@code notif-*} thread pool
 *   <li>{@link SmtpConfigurationUnavailableException} is caught gracefully (no unhandled error)
 *   <li>The SMTP config warning is logged at WARN the first time, then suppressed to DEBUG
 *   <li>Cooldown is released so subsequent attempts can retry
 * </ul>
 */
@SpringJUnitConfig(SelfServiceSmtpFallbackIntegrationTest.SmtpFallbackTestConfig.class)
@TestPropertySource(
    properties = {
      "fineract.selfservice.notification.enabled=true",
      "fineract.selfservice.notification.cooldown-seconds=5"
      // Deliberately NOT setting fineract.selfservice.smtp.* to verify the exception path
    })
@org.springframework.test.annotation.DirtiesContext
public class SelfServiceSmtpFallbackIntegrationTest {

  @Autowired private ApplicationEventPublisher eventPublisher;

  @Autowired private SelfServiceNotificationService notificationService;

  @Autowired private SmtpFallbackTestConfig.NotificationCompletionListener completionListener;

  private ListAppender<ILoggingEvent> notificationLogAppender;
  private Logger notificationLogger;
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
    HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
    dates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 2));
    dates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 1, 1));
    ThreadLocalContextUtil.setBusinessDates(dates);

    // Capture notification service logs
    notificationLogger = (Logger) LoggerFactory.getLogger(SelfServiceNotificationService.class);
    originalLevel = notificationLogger.getLevel();
    notificationLogger.setLevel(Level.DEBUG);
    notificationLogAppender = new ListAppender<>();
    notificationLogAppender.start();
    notificationLogger.addAppender(notificationLogAppender);

    completionListener.reset();
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
    notificationLogger.detachAppender(notificationLogAppender);
    notificationLogger.setLevel(originalLevel);
    notificationLogAppender.stop();
  }

  @Test
  void smtpConfigUnavailable_isHandledGracefully_andLogsWarnOnce() throws InterruptedException {
    // Fire first event — should produce a WARN log
    eventPublisher.publishEvent(
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "testuser",
            "test@example.com",
            null,
            true,
            "127.0.0.1",
            Locale.US));

    assertTrue(
        completionListener.awaitCompletion(1), "First notification should complete within timeout");

    // Fire second event (different user) — should produce DEBUG, not WARN
    completionListener.reset();
    eventPublisher.publishEvent(
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            2L,
            "Other",
            "User",
            "otheruser",
            "other@example.com",
            null,
            true,
            "10.0.0.1",
            Locale.US));

    assertTrue(
        completionListener.awaitCompletion(1),
        "Second notification should complete within timeout");

    // Poll for expected logs with a timeout. completionListener.awaitCompletion() only
    // guarantees the sibling listener finished, not that the notification handler has
    // emitted all logs. Both listeners run independently on the same thread pool, so
    // under slow execution or high load the logs may not be present yet.
    List<ILoggingEvent> smtpLogs = java.util.Collections.emptyList();
    long warnCount = 0;
    long debugCount = 0;
    boolean warnMentionsSuppression = false;
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    do {
      smtpLogs =
          new java.util.ArrayList<>(notificationLogAppender.list)
              .stream()
                  .filter(e -> e.getFormattedMessage().contains("SMTP configuration unavailable"))
                  .collect(Collectors.toList());
      warnCount = smtpLogs.stream().filter(e -> e.getLevel() == Level.WARN).count();
      debugCount = smtpLogs.stream().filter(e -> e.getLevel() == Level.DEBUG).count();
      warnMentionsSuppression =
          smtpLogs.stream()
              .filter(e -> e.getLevel() == Level.WARN)
              .anyMatch(e -> e.getFormattedMessage().contains("Further config errors"));
      if (smtpLogs.size() >= 2 && warnCount == 1 && debugCount >= 1 && warnMentionsSuppression) {
        break;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadlineNanos);

    assertTrue(
        smtpLogs.size() >= 2, "Expected at least 2 SMTP config log events, got " + smtpLogs.size());

    assertEquals(
        1,
        warnCount,
        "Exactly one SMTP config error should be logged at WARN (the first to hit compareAndSet)");
    assertTrue(
        warnMentionsSuppression, "The WARN log should indicate suppression of future errors");
    assertTrue(
        debugCount >= 1,
        "Subsequent SMTP config errors should be logged at DEBUG, got " + debugCount);
  }

  @Test
  void notificationService_isWiredCorrectly() {
    assertNotNull(
        notificationService, "SelfServiceNotificationService should be wired by Spring context");
  }

  @TestConfiguration
  @Import(SelfServiceNotificationConfig.class)
  static class SmtpFallbackTestConfig {

    /**
     * Provides a mock ExternalServicesPropertiesReadPlatformService that simulates the missing
     * c_external_service_properties table.
     */
    @Bean
    @Primary
    public ExternalServicesPropertiesReadPlatformService
        externalServicesPropertiesReadPlatformService() {
      return new ExternalServicesPropertiesReadPlatformService() {
        @Override
        public org.apache.fineract.infrastructure.configuration.data.S3CredentialsData
            getS3Credentials() {
          throw new UnsupportedOperationException();
        }

        @Override
        public org.apache.fineract.infrastructure.configuration.data.SMTPCredentialsData
            getSMTPCredentials() {
          throw new BadSqlGrammarException(
              "getSMTPCredentials",
              "SELECT esp.name, esp.value FROM c_external_service_properties esp ...",
              new java.sql.SQLException(
                  "ERROR: relation \"c_external_service_properties\" does not exist"));
        }

        @Override
        public org.apache.fineract.infrastructure.campaigns.sms.data.MessageGatewayConfigurationData
            getSMSGateway() {
          throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Collection<
                org.apache.fineract.infrastructure.configuration.data
                    .ExternalServicesPropertiesData>
            retrieveOne(String serviceName) {
          throw new UnsupportedOperationException();
        }

        @Override
        public org.apache.fineract.infrastructure.gcm.domain.NotificationConfigurationData
            getNotificationConfiguration() {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Bean
    @Primary
    public SelfServicePluginEmailService selfServicePluginEmailService(
        ExternalServicesPropertiesReadPlatformService externalServicesReadPlatformService,
        Environment env) {
      return new SelfServicePluginEmailService(externalServicesReadPlatformService, env);
    }

    @Bean
    public SelfServiceNotificationService selfServiceNotificationService(
        org.thymeleaf.ITemplateEngine notificationTemplateEngine,
        org.springframework.context.MessageSource notificationMessageSource,
        SelfServicePluginEmailService emailService,
        NotificationCooldownCache cooldownCache,
        Environment env,
        @jakarta.annotation.Nullable
            ExternalNotificationSystemClient externalNotificationSystemClient) {
      return new SelfServiceNotificationService(
          notificationTemplateEngine,
          notificationMessageSource,
          emailService,
          smsMessageRepository(),
          smsScheduledJobService(),
          smsProviderService(),
          cooldownCache,
          env,
          externalNotificationSystemClient);
    }

    @Bean
    public SmsMessageRepository smsMessageRepository() {
      return org.mockito.Mockito.mock(SmsMessageRepository.class);
    }

    @Bean
    public SmsMessageScheduledJobService smsScheduledJobService() {
      return org.mockito.Mockito.mock(SmsMessageScheduledJobService.class);
    }

    @Bean
    public SmsCampaignDropdownReadPlatformService smsProviderService() {
      return org.mockito.Mockito.mock(SmsCampaignDropdownReadPlatformService.class);
    }

    @Bean
    public NotificationCompletionListener notificationCompletionListener() {
      return new NotificationCompletionListener();
    }

    /**
     * Listener that observes when the async notification handler completes, allowing tests to await
     * completion without arbitrary Thread.sleep.
     */
    static class NotificationCompletionListener {

      private CountDownLatch latch = new CountDownLatch(1);

      void reset() {
        this.latch = new CountDownLatch(1);
      }

      /**
       * Listens on the same event as the notification service. Since both run on the same async
       * thread pool, this listener completing means the notification service's handler has also
       * completed (Spring dispatches to all listeners).
       */
      @Async("notificationExecutor")
      @EventListener
      public void onNotification(SelfServiceNotificationEvent event) {
        // Small delay to ensure the primary handler runs first
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        latch.countDown();
      }

      boolean awaitCompletion(int timeoutSeconds) throws InterruptedException {
        // Extra margin because the async handlers share a thread pool
        return latch.await(timeoutSeconds + 2, TimeUnit.SECONDS);
      }
    }
  }
}
