/**
 * Copyright since 2026 Mifos Initiative
 */
package org.apache.fineract.selfservice.notification.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.selfservice.notification.NotificationContext;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationDispatcher;
import org.apache.fineract.selfservice.notification.SelfServiceTemplateService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SelfServiceNotificationEventListener {

    private final SelfServiceNotificationDispatcher dispatcher;
    private final SelfServiceTemplateService templateService;

    @Async // Critical for non-blocking OTP delivery
    @EventListener
    @Transactional(readOnly = true) // templates only
    public void handleNotification(SelfServiceNotificationEvent event) {
        String eventTypeStr = event.getType().name();
        try (NotificationContext.Scope ignored = NotificationContext.bind(eventTypeStr)) {

            log.info("Processing {} notification for userId={}", event.getType(), event.getUserId());

            Map<String, Object> params = new HashMap<>(event.getContextData());
            params.put("firstName", event.getFirstName());
            params.put("lastName", event.getLastName());
            params.put("username", event.getUsername());
            params.put("ipAddress", event.getIpAddress() != null ? event.getIpAddress() : "N/A");

            String channel = event.isEmailMode() ? "EMAIL" : "SMS";
            String mergedContent = templateService.mergeTemplate(event.getType(), channel, params);

            if (event.isEmailMode()) {
                String subject = getSubjectForType(event.getType());
                dispatcher.sendEmail(event.getEmail(), subject, mergedContent);
            } else {
                String mobile = event.getMobileNumber();
                if (mobile != null) {
                    dispatcher.sendSms(mobile, mergedContent);
                } else {
                    log.warn("No mobile for SMS notification, falling back to email if available.");
                    if (event.getEmail() != null) {
                        dispatcher.sendEmail(event.getEmail(), getSubjectForType(event.getType()), mergedContent);
                    }
                }
            }

            log.info("Successfully dispatched {} notification", event.getType());
        } catch (Exception e) {
            log.error("Failed to handle notification event {}", event.getType(), e);
        }
    }

    private String getSubjectForType(SelfServiceNotificationEvent.Type type) {
        return switch (type) {
            case TRANSFER_OTP -> "Your Transfer OTP Code";
            case TRANSFER_SUCCESS -> "Transfer Completed Successfully";
            default -> "Mifos Self-Service Notification";
        };
    }
}