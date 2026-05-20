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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.fineract.infrastructure.campaigns.sms.data.SmsProviderData;
import org.apache.fineract.infrastructure.campaigns.sms.service.SmsCampaignDropdownReadPlatformService;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;
import org.apache.fineract.infrastructure.configuration.domain.NotificationMessage;
import org.apache.fineract.infrastructure.core.domain.EmailDetail;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService;
import org.apache.fineract.infrastructure.core.service.SmtpConfigurationUnavailableException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.sms.domain.SmsMessage;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository;
import org.apache.fineract.infrastructure.sms.domain.SmsMessageStatusType;
import org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Service responsible for sending self-service notifications (email and SMS) in response to
 * user-triggered events such as login, password changes, and account updates.
 */
@Slf4j
@Service
public class SelfServiceNotificationService {

  private final ITemplateEngine notificationTemplateEngine;
  private final MessageSource notificationMessageSource;
  private final SelfServicePluginEmailService emailService;
  private final SmsMessageRepository smsMessageRepository;
  private final SmsMessageScheduledJobService smsScheduledJobService;
  private final SmsCampaignDropdownReadPlatformService smsProviderService;
  private final NotificationCooldownCache notificationCooldownCache;
  private final Environment env;
  private final ExternalNotificationSystemClient externalNotificationSystemClient;

  /**
   * Guards the one-time WARN log emitted when email delivery fails due to SMTP configuration being
   * unavailable. Since SMTP config is global, only the first occurrence is logged at WARN;
   * subsequent occurrences are logged at DEBUG to avoid log spam.
   */
  private final AtomicBoolean smtpConfigWarningLogged = new AtomicBoolean(false);

  public SelfServiceNotificationService(
      ITemplateEngine notificationTemplateEngine,
      MessageSource notificationMessageSource,
      SelfServicePluginEmailService emailService,
      SmsMessageRepository smsMessageRepository,
      SmsMessageScheduledJobService smsScheduledJobService,
      SmsCampaignDropdownReadPlatformService smsProviderService,
      NotificationCooldownCache notificationCooldownCache,
      Environment env,
      @Nullable ExternalNotificationSystemClient externalNotificationSystemClient) {
    this.notificationTemplateEngine = notificationTemplateEngine;
    this.notificationMessageSource = notificationMessageSource;
    this.emailService = emailService;
    this.smsMessageRepository = smsMessageRepository;
    this.smsScheduledJobService = smsScheduledJobService;
    this.smsProviderService = smsProviderService;
    this.notificationCooldownCache = notificationCooldownCache;
    this.env = env;
    this.externalNotificationSystemClient = externalNotificationSystemClient;
  }

  /**
   * Handles self-service notification events asynchronously.
   *
   * <p>Sends email or SMS notifications based on event configuration, user preferences, and
   * global/per-event feature flags. Events are subject to a per-user cooldown to avoid duplicate
   * notifications in rapid succession.
   *
   * <p>This method executes on the {@code notificationExecutor} thread pool and returns immediately
   * to the caller. Any exception during processing is logged but does not propagate.
   *
   * @param event the notification event containing user details and notification type
   */
  @Async("notificationExecutor")
  @EventListener
  // REMOVED @Transactional - it conflicts with @Async and tenant context propagation
  // Individual service calls manage their own transactions if needed
  public void handleNotification(SelfServiceNotificationEvent event) {
    // Restore tenant context FIRST, before any DB operations
    restoreTenantContext(event);
    try {
      boolean globalEnabled =
          env.getProperty("fineract.selfservice.notification.enabled", Boolean.class, true);
      if (!globalEnabled) {
        return;
      }

      boolean eventEnabled =
          env.getProperty(
              "fineract.selfservice.notification."
                  + event.getType().getTemplatePrefix()
                  + ".enabled",
              Boolean.class,
              event.getType() != SelfServiceNotificationEvent.Type.LOGIN_FAILURE);
      if (!eventEnabled) {
        return;
      }

      String cacheKey = event.getType().name() + ":" + event.getUserId();
      if (!notificationCooldownCache.tryAcquire(cacheKey)) {
        log.debug(
            "Skipping notification for event type {} and user ID {} due to cooldown.",
            event.getType(),
            event.getUserId());
        return;
      }

      Context context =
          new Context(
              event.getLocale() != null ? event.getLocale() : java.util.Locale.getDefault());
      context.setVariable("firstName", event.getFirstName());
      context.setVariable("lastName", event.getLastName());
      context.setVariable("username", event.getUsername());
      if (event.getIpAddress() != null) {
        context.setVariable("ipAddress", event.getIpAddress());
      }
      context.setVariable("eventTimestamp", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));

      String subjectKey = "subject." + event.getType().getTemplatePrefix();
      String subject =
          notificationMessageSource.getMessage(subjectKey, null, subjectKey, context.getLocale());

      if (event.isEmailMode()) {
        sendEmailNotification(event, subject, context);
      } else {
        sendSmsNotification(event, subject, context);
      }
    } catch (org.apache.fineract.infrastructure.core.service.PlatformEmailSendException emailEx) {
      if (emailEx.getCause() instanceof SmtpConfigurationUnavailableException) {
        handleSmtpConfigError(event, (SmtpConfigurationUnavailableException) emailEx.getCause());
      } else {
        String cacheKey = event.getType().name() + ":" + event.getUserId();
        notificationCooldownCache.release(cacheKey);
        log.error("Failed to handle notification for event type {}", event.getType(), emailEx);
      }
    } catch (Exception e) {
      String cacheKey = event.getType().name() + ":" + event.getUserId();
      notificationCooldownCache.release(cacheKey);
      log.error("Failed to handle notification for event type {}", event.getType(), e);
    } finally {
      // CRITICAL: Clean up ThreadLocal to prevent tenant context leakage in thread pool
      // This ensures the next task on this reused thread starts with a clean slate
      ThreadLocalContextUtil.reset();
      log.debug(
          "Reset tenant context on thread {} after notification processing",
          Thread.currentThread().getName());
    }
  }

  private void sendEmailNotification(
      SelfServiceNotificationEvent event, String subject, Context context) {

    NotificationCredentialsData notificationCredentials = null;
    if (externalNotificationSystemClient != null) {
      notificationCredentials = externalNotificationSystemClient.resolveNotificationCredentials();
    }
    String templateName = "html/" + event.getType().getTemplatePrefix();
    String htmlBody = notificationTemplateEngine.process(templateName, context);

    if (notificationCredentials != null && notificationCredentials.isEnabled()) {

      NotificationMessage notificationMessage = new NotificationMessage();

      notificationMessage.setEmail(event.getEmail());
      notificationMessage.setMobile(event.getMobileNumber());

      if (notificationCredentials.isWhatsapp() || notificationCredentials.isSms()) {
        // 1. Remove all HTML tags using regex
        String noTags = htmlBody.replaceAll("<[^>]*>", "");

        // 2. Unescape HTML entities (e.g., &nbsp; or &amp;)
        String unescaped = StringEscapeUtils.unescapeHtml4(noTags);

        // 3. Clean up whitespace:
        // Trim leading/trailing and replace 3+ consecutive newlines with 2
        String result =
            unescaped.trim().replaceAll("(?m)^[ \t]*\r?\n", "").replaceAll("\n{3,}", "\n\n");
        notificationMessage.setText(result);
      }

      if (notificationCredentials.isEmail()) {
        notificationMessage.setText(htmlBody);
      }

      try {

        externalNotificationSystemClient.sendPostRequest(notificationMessage);
      } catch (Exception e) {
        log.error("Error when sending to external system ", e.getMessage());
        e.printStackTrace();
      }
    } else {
      if (org.apache.commons.lang3.StringUtils.isBlank(event.getEmail())) {
        log.warn(
            "Email notification skipped for event {} because no email address is available",
            event.getType());
        releaseCooldown(event);
        return;
      }

      String recipientName = buildRecipientName(event.getFirstName(), event.getLastName());
      EmailDetail emailDetail = new EmailDetail(subject, htmlBody, event.getEmail(), recipientName);
      emailService.sendFormattedEmail(emailDetail);
    }
  }

  private void sendSmsNotification(
      SelfServiceNotificationEvent event, String subject, Context context) {
    if (org.apache.commons.lang3.StringUtils.isBlank(event.getMobileNumber())) {
      log.warn(
          "SMS notification skipped for event {} because no mobile number is available",
          event.getType());
      releaseCooldown(event);
      return;
    }
    Collection<SmsProviderData> providers = smsProviderService.retrieveSmsProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn(
          "No SMS provider configured, SMS notification skipped for event {}", event.getType());
      releaseCooldown(event);
      return;
    }
    Long providerId = null;
    if (providers.size() == 1) {
      providerId = providers.iterator().next().getId();
    } else {
      Long configuredProviderId =
          env.getProperty("fineract.selfservice.notification.sms.providerId", Long.class);
      if (configuredProviderId != null) {
        for (SmsProviderData provider : providers) {
          if (configuredProviderId.equals(provider.getId())) {
            providerId = provider.getId();
            break;
          }
        }
      }
    }
    if (providerId == null) {
      log.warn(
          "Multiple SMS providers available but no default specified, SMS notification skipped for event {}",
          event.getType());
      releaseCooldown(event);
      return;
    }

    String templateName = "text/" + event.getType().getTemplatePrefix();
    String textBody = notificationTemplateEngine.process(templateName, context);

    SmsMessage smsMessage =
        SmsMessage.instance(
            null,
            null,
            null,
            null,
            SmsMessageStatusType.PENDING,
            textBody,
            event.getMobileNumber(),
            null,
            true);
    smsMessage = smsMessageRepository.save(smsMessage);
    try {
      smsScheduledJobService.sendTriggeredMessage(new ArrayList<>(List.of(smsMessage)), providerId);
      smsMessage.setStatusType(SmsMessageStatusType.SENT.getValue());
      smsMessageRepository.save(smsMessage);
    } catch (Exception e) {
      smsMessage.setStatusType(SmsMessageStatusType.FAILED.getValue());
      smsMessageRepository.save(smsMessage);
      throw e;
    }
  }

  /**
   * Handles SMTP configuration errors by releasing the cooldown (so the event can be retried
   * immediately after the config is fixed) and logging at WARN only on the first occurrence. SMTP
   * config is global, so once we've logged the warning once, subsequent failures for any user/event
   * are logged at DEBUG to avoid log spam.
   */
  private void handleSmtpConfigError(
      SelfServiceNotificationEvent event, SmtpConfigurationUnavailableException configEx) {
    String cacheKey = event.getType().name() + ":" + event.getUserId();
    notificationCooldownCache.release(cacheKey);

    if (smtpConfigWarningLogged.compareAndSet(false, true)) {
      log.warn(
          "Email notification skipped for event type {} — SMTP configuration unavailable: {}. "
              + "Further config errors will be logged at DEBUG.",
          event.getType(),
          configEx.getMessage());
    } else {
      log.debug(
          "Email notification skipped for event type {} — SMTP configuration unavailable.",
          event.getType());
    }
  }

  private String buildRecipientName(String firstName, String lastName) {
    StringBuilder name = new StringBuilder();
    if (firstName != null && !firstName.isBlank()) {
      name.append(firstName);
    }
    if (lastName != null && !lastName.isBlank()) {
      if (name.length() > 0) {
        name.append(" ");
      }
      name.append(lastName);
    }
    return name.length() > 0 ? name.toString() : "User";
  }

  private void releaseCooldown(SelfServiceNotificationEvent event) {
    String cacheKey = event.getType().name() + ":" + event.getUserId();
    notificationCooldownCache.release(cacheKey);
  }

  /**
   * Restores the Fineract tenant context and business dates from the event onto the current thread.
   * This is critical for async listeners running on the notification executor pool, where the
   * original request thread's {@code ThreadLocal} tenant context may not have been propagated (e.g.
   * when events are published from {@code afterCommit()} callbacks after the auth filter has
   * already cleared the context).
   *
   * <p>The event-carried tenant takes precedence because it was captured at event creation time on
   * the originating thread. The {@code TaskDecorator} in {@link
   * org.apache.fineract.selfservice.notification.starter.SelfServiceNotificationConfig} serves as a
   * belt-and-suspenders fallback.
   */
  void restoreTenantContext(SelfServiceNotificationEvent event) {
    FineractPlatformTenant eventTenant = event.getTenant();
    if (eventTenant != null) {
      ThreadLocalContextUtil.setTenant(eventTenant);
      log.debug(
          "Restored tenant '{}' from notification event on thread {}",
          eventTenant.getTenantIdentifier(),
          Thread.currentThread().getName());
    } else {
      FineractPlatformTenant threadTenant = null;
      try {
        threadTenant = ThreadLocalContextUtil.getTenant();
      } catch (IllegalStateException ignored) {
        // getTenant() may throw on some Fineract versions
      }
      if (threadTenant != null) {
        log.debug(
            "Using TaskDecorator-propagated tenant '{}' on thread {}",
            threadTenant.getTenantIdentifier(),
            Thread.currentThread().getName());
      } else {
        log.warn(
            "No tenant context available for notification event {} on thread {} — "
                + "database operations may fail",
            event.getType(),
            Thread.currentThread().getName());
      }
    }
    if (event.getBusinessDates() != null) {
      ThreadLocalContextUtil.setBusinessDates(event.getBusinessDates());
    }
  }
}
