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

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import org.apache.fineract.selfservice.notification.SelfServiceTemplateService;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service responsible for sending self-service notifications (email, SMS, WhatsApp, and In-App) in
 * response to user-triggered events such as login, password changes, and account updates. Uses
 * Fineract core Template entities for content generation.
 */
@Slf4j
@Service
public class SelfServiceNotificationService {

  private final SelfServiceTemplateService selfServiceTemplateService;
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
   * unavailable.
   */
  private final AtomicBoolean smtpConfigWarningLogged = new AtomicBoolean(false);

  public SelfServiceNotificationService(
      SelfServiceTemplateService selfServiceTemplateService,
      MessageSource notificationMessageSource,
      SelfServicePluginEmailService emailService,
      SmsMessageRepository smsMessageRepository,
      SmsMessageScheduledJobService smsScheduledJobService,
      SmsCampaignDropdownReadPlatformService smsProviderService,
      NotificationCooldownCache notificationCooldownCache,
      Environment env,
      @Nullable ExternalNotificationSystemClient externalNotificationSystemClient) {
    this.selfServiceTemplateService = selfServiceTemplateService;
    this.notificationMessageSource = notificationMessageSource;
    this.emailService = emailService;
    this.smsMessageRepository = smsMessageRepository;
    this.smsScheduledJobService = smsScheduledJobService;
    this.smsProviderService = smsProviderService;
    this.notificationCooldownCache = notificationCooldownCache;
    this.env = env;
    this.externalNotificationSystemClient = externalNotificationSystemClient;
  }

  @Async("notificationExecutor")
  @EventListener
  public void handleNotification(SelfServiceNotificationEvent event) {
    // Restore tenant context FIRST, before any DB operations
    restoreTenantContext(event);
    try {
      boolean globalEnabled =
          env.getProperty("fineract.selfservice.notification.enabled", Boolean.class, true);
      if (!globalEnabled) return;

      boolean eventEnabled =
          env.getProperty(
              "fineract.selfservice.notification."
                  + event.getType().getTemplatePrefix()
                  + ".enabled",
              Boolean.class,
              event.getType() != SelfServiceNotificationEvent.Type.LOGIN_FAILURE);
      if (!eventEnabled) return;

      String cacheKey = event.getType().name() + ":" + event.getUserId();
      if (!notificationCooldownCache.tryAcquire(cacheKey)) {
        log.debug(
            "Skipping notification for event type {} and user ID {} due to cooldown.",
            event.getType(),
            event.getUserId());
        return;
      }

      Map<String, Object> params = buildTemplateParams(event);

      // Check if external notification system is enabled
      NotificationCredentialsData notificationCredentials = null;
      if (externalNotificationSystemClient != null) {
        try {
          notificationCredentials =
              externalNotificationSystemClient.resolveNotificationCredentials();
        } catch (Exception e) {
          log.error("Failed to resolve notification credentials", e);
        }
      }

      if (notificationCredentials != null && notificationCredentials.isEnabled()) {
        sendExternalNotification(event, notificationCredentials, params);
      } else {
        // Fallback to standard email/SMS services
        if (event.isEmailMode()) {
          sendEmailNotification(event, params);
        } else {
          sendSmsNotification(event, params);
        }
      }
    } catch (org.apache.fineract.infrastructure.core.service.PlatformEmailSendException emailEx) {
      if (emailEx.getCause() instanceof SmtpConfigurationUnavailableException) {
        handleSmtpConfigError(event, (SmtpConfigurationUnavailableException) emailEx.getCause());
      } else {
        releaseCooldown(event);
        log.error("Failed to handle notification for event type {}", event.getType(), emailEx);
      }
    } catch (Exception e) {
      releaseCooldown(event);
      log.error("Failed to handle notification for event type {}", event.getType(), e);
    } finally {
      // CRITICAL: Clean up ThreadLocal to prevent tenant context leakage in thread pool
      ThreadLocalContextUtil.reset();
      log.debug(
          "Reset tenant context on thread {} after notification processing",
          Thread.currentThread().getName());
    }
  }

  private Map<String, Object> buildTemplateParams(SelfServiceNotificationEvent event) {
    Map<String, Object> params = new HashMap<>();

    // Keys for the new Fineract Template engine
    params.put("firstname", event.getFirstName());
    params.put("lastname", event.getLastName());
    params.put("username", event.getUsername());
    params.put("email", event.getEmail());
    params.put("mobileNumber", event.getMobileNumber());
    params.put("clientIp", event.getIpAddress());
    params.put("locale", event.getLocale() != null ? event.getLocale().toString() : "en");
    params.put("emailMode", event.isEmailMode());
    
    // Merge context data (e.g., requestId, authCode)
    if (event.getContextData() != null) {
      params.putAll(event.getContextData());
    }

    // Legacy keys kept for backward compatibility with any existing custom logic
    params.put("firstName", event.getFirstName());
    params.put("lastName", event.getLastName());
    params.put("ipAddress", event.getIpAddress());
    params.put("eventTimestamp", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));

    return params;
  }

  private void sendExternalNotification(
      SelfServiceNotificationEvent event,
      NotificationCredentialsData credentials,
      Map<String, Object> params) {
    NotificationMessage notificationMessage = new NotificationMessage();
    notificationMessage.setEmail(event.getEmail());
    notificationMessage.setMobile(event.getMobileNumber());

    // Determine which template to use based on available contact info and external client
    // capabilities
    String content;
    if (StringUtils.isNotBlank(event.getMobileNumber())) {
      content =
          selfServiceTemplateService.mergeTemplate(
              event.getType(), credentials.isWhatsapp() ? "WHATSAPP" : "SMS", params);
    } else {
      content = selfServiceTemplateService.mergeTemplate(event.getType(), "EMAIL", params);
    }

    // Strip HTML tags if the content is going to SMS/WhatsApp channels
    if (credentials.isWhatsapp() || credentials.isSms()) {
      String noTags = content.replaceAll("<[^>]*>", "");
      String unescaped = StringEscapeUtils.unescapeHtml4(noTags);
      content = unescaped.trim().replaceAll("(?m)^[ \t]*\r?\n", "").replaceAll("\n{3,}", "\n\n");
    }

    notificationMessage.setText(content);

    try {
      externalNotificationSystemClient.sendPostRequest(notificationMessage);
    } catch (Exception e) {
      log.error("Error when sending to external system: {}", e.getMessage(), e);
      releaseCooldown(event);
    }
  }

  private void sendEmailNotification(
      SelfServiceNotificationEvent event, Map<String, Object> params) {
    if (StringUtils.isBlank(event.getEmail())) {
      log.warn(
          "Email notification skipped for event {} because no email address is available",
          event.getType());
      releaseCooldown(event);
      return;
    }

    String subjectKey = "subject." + event.getType().getTemplatePrefix();
    java.util.Locale locale =
        event.getLocale() != null ? event.getLocale() : java.util.Locale.getDefault();
    String subject = notificationMessageSource.getMessage(subjectKey, null, subjectKey, locale);

    // Fetch the EMAIL template content using the new service
    String htmlBody = selfServiceTemplateService.mergeTemplate(event.getType(), "EMAIL", params);

    String recipientName = buildRecipientName(event.getFirstName(), event.getLastName());
    EmailDetail emailDetail = new EmailDetail(subject, htmlBody, event.getEmail(), recipientName);
    emailService.sendFormattedEmail(emailDetail);
  }

  private void sendSmsNotification(SelfServiceNotificationEvent event, Map<String, Object> params) {
    if (StringUtils.isBlank(event.getMobileNumber())) {
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

    Long providerId = resolveSmsProviderId(providers);
    if (providerId == null) {
      log.warn(
          "No valid SMS provider found, SMS notification skipped for event {}", event.getType());
      releaseCooldown(event);
      return;
    }

    // Fetch the SMS template content using the new service
    String textBody = selfServiceTemplateService.mergeTemplate(event.getType(), "SMS", params);

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
      releaseCooldown(event);
      throw e;
    }
  }

  private Long resolveSmsProviderId(Collection<SmsProviderData> providers) {
    if (providers.size() == 1) {
      return providers.iterator().next().getId();
    }
    Long configuredProviderId =
        env.getProperty("fineract.selfservice.notification.sms.providerId", Long.class);
    if (configuredProviderId != null) {
      for (SmsProviderData provider : providers) {
        if (configuredProviderId.equals(provider.getId())) {
          return provider.getId();
        }
      }
    }
    return null;
  }

  private void handleSmtpConfigError(
      SelfServiceNotificationEvent event, SmtpConfigurationUnavailableException configEx) {
    releaseCooldown(event);
    if (smtpConfigWarningLogged.compareAndSet(false, true)) {
      log.warn(
          "Email notification skipped for event type {} — SMTP configuration unavailable: {}. Further config errors will be logged at DEBUG.",
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
    if (firstName != null && !firstName.isBlank()) name.append(firstName);
    if (lastName != null && !lastName.isBlank()) {
      if (name.length() > 0) name.append(" ");
      name.append(lastName);
    }
    return name.length() > 0 ? name.toString() : "User";
  }

  private void releaseCooldown(SelfServiceNotificationEvent event) {
    String cacheKey = event.getType().name() + ":" + event.getUserId();
    notificationCooldownCache.release(cacheKey);
  }

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
      }
      if (threadTenant != null) {
        log.debug(
            "Using TaskDecorator-propagated tenant '{}' on thread {}",
            threadTenant.getTenantIdentifier(),
            Thread.currentThread().getName());
      } else {
        log.warn(
            "No tenant context available for notification event {} on thread {} — database operations may fail",
            event.getType(),
            Thread.currentThread().getName());
      }
    }
    if (event.getBusinessDates() != null) {
      ThreadLocalContextUtil.setBusinessDates(event.getBusinessDates());
    }
  }
}
