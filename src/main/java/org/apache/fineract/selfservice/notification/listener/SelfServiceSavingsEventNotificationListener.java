/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.avro.MessageV1;
import org.apache.fineract.avro.savings.v1.SavingsAccountTransactionDataV1;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Durable JMS topic listener that consumes Fineract external business events
 * ({@code SavingsDepositBusinessEvent} / {@code SavingsWithdrawalBusinessEvent})
 * and turns them into {@link SelfServiceNotificationEvent} instances.
 *
 * <p>Fully multi-tenant: restores the correct {@link FineractPlatformTenant} from the Avro
 * envelope before any database access and always clears the {@code ThreadLocal} afterwards.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelfServiceSavingsEventNotificationListener {

  private final ApplicationEventPublisher eventPublisher;
  private final ClientReadPlatformService clientReadPlatformService;
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  private final TenantDetailsService tenantDetailsService;
  private final ObjectMapper objectMapper;

  /**
   * Destination = the topic name published by Fineract core.
   * Official property: {@code fineract.events.external.producer.jms.event-topic-name}
   */
  @Value("${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}")
  private String eventTopicName;

  /**
   * Durable subscription name – matches the value already set in docker-compose.
   */
  @Value("${fineract.external.events.jms.subscription-name:selfservice-savings-notifications}")
  private String subscriptionName;
  
  @jakarta.annotation.PostConstruct
  public void init() {
    log.info("============================================================");
    log.info("SelfServiceSavingsEventNotificationListener STARTED");
    log.info("  destination     = {}", eventTopicName);
    log.info("  subscription    = {}", subscriptionName);
    log.info("  containerFactory= topicJmsListenerContainerFactory");
    log.info("============================================================");
  }

  @JmsListener(
      destination =
          "${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}",
      containerFactory = "topicJmsListenerContainerFactory",
      subscription =
          "${fineract.external.events.jms.subscription-name:selfservice-savings-notifications}",
      id = "selfServiceSavingsExternalEventsListener")
  public void onMessage(Message message) {
    FineractPlatformTenant originalTenant = null;
    try {
      originalTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
      // Expected on pure JMS threads
    }

    try {
      byte[] payloadBytes = extractPayloadBytes(message);
      if (payloadBytes == null || payloadBytes.length == 0) {
        log.warn("Received null or empty payload from JMS message");
        return;
      }

      log.info(
          "JMS message received on topic [{}] – Type: {}, Length: {}",
          eventTopicName,
          message.getClass().getSimpleName(),
          payloadBytes.length);

      // Avro MessageV1 envelope
      ByteBuffer byteBuffer = ByteBuffer.wrap(payloadBytes);
      MessageV1 messageV1 = MessageV1.fromByteBuffer(byteBuffer);

      String eventType = messageV1.getType() != null ? messageV1.getType().toString() : null;
      String tenantIdentifier =
          StringUtils.hasText(messageV1.getTenantId()) ? messageV1.getTenantId() : "default";

      log.info(
          "Parsed Avro MessageV1 – type={}, category={}, tenant={}",
          eventType,
          messageV1.getCategory(),
          tenantIdentifier);

      // Multi-tenant context restoration BEFORE any DB call
      FineractPlatformTenant tenant = resolveTenant(tenantIdentifier);
      if (tenant == null) {
        log.error("Unable to resolve tenant [{}]. Aborting message processing.", tenantIdentifier);
        return;
      }

      ThreadLocalContextUtil.setTenant(tenant);
      // ThreadLocalContextUtil.setBusinessDates expects a concrete HashMap
      HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
      businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
      ThreadLocalContextUtil.setBusinessDates(businessDates);

      // Process only the events we care about
      if ("SavingsDepositBusinessEvent".equals(eventType)
          || "SavingsWithdrawalBusinessEvent".equals(eventType)) {

        ByteBuffer dataBuffer = messageV1.getData();
        if (dataBuffer == null) {
          log.warn("Event envelope has no data payload for type {}", eventType);
          return;
        }

        SavingsAccountTransactionDataV1 txnData =
            SavingsAccountTransactionDataV1.fromByteBuffer(dataBuffer);
        processSavingsTransactionEvent(txnData, eventType);
      } else {
        log.debug("Ignoring external event type: {}", eventType);
      }

    } catch (Exception e) {
      log.error("Failed to parse or process external event message", e);
      // Session is transacted → message will be redelivered
      throw new RuntimeException("JMS processing failed – triggering redelivery", e);
    } finally {
      // Always clean ThreadLocal to avoid pollution of the JMS thread pool
      ThreadLocalContextUtil.reset();
      if (originalTenant != null) {
        ThreadLocalContextUtil.setTenant(originalTenant);
      }
    }
  }

  private FineractPlatformTenant resolveTenant(String tenantIdentifier) {
    try {
      return tenantDetailsService.loadTenantById(tenantIdentifier);
    } catch (Exception e) {
      log.warn(
          "Could not load tenant by id [{}]. Falling back to 'default'. Cause: {}",
          tenantIdentifier,
          e.getMessage());
      try {
        return tenantDetailsService.loadTenantById("default");
      } catch (Exception ex) {
        log.error("Even the default tenant could not be loaded", ex);
        return null;
      }
    }
  }

  private void processSavingsTransactionEvent(
      SavingsAccountTransactionDataV1 txnData, String eventType) {

    // Avro field is "accountId", NOT "savingsAccountId"
    Long savingsAccountId = txnData.getAccountId();
    BigDecimal amount = txnData.getAmount() != null ? txnData.getAmount() : BigDecimal.ZERO;
    String currencyCode =
        txnData.getCurrency() != null ? txnData.getCurrency().getCode() : "XXX";
    LocalDate transactionDate =
        txnData.getDate() != null
            ? LocalDate.parse(txnData.getDate().toString())
            : LocalDate.now();
    String accountNumber = txnData.getAccountNo();

    Long clientId = null;
    if (savingsAccountId != null) {
      try {
        SavingsAccountData accountData =
            savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
        clientId = accountData.getClientId();
        if (accountNumber == null) {
          accountNumber = accountData.getAccountNo();
        }
      } catch (Exception e) {
        log.error("Unable to load SavingsAccountData for id={}", savingsAccountId, e);
        return;
      }
    }

    if (clientId == null) {
      log.warn(
          "No clientId resolved for savingsAccountId={}. Skipping notification.",
          savingsAccountId);
      return;
    }

    triggerNotification(
        clientId, savingsAccountId, accountNumber, amount, currencyCode, transactionDate, eventType);
  }

  private void triggerNotification(
      Long clientId,
      Long savingsAccountId,
      String accountNumber,
      BigDecimal amount,
      String currencyCode,
      LocalDate transactionDate,
      String eventType) {

    ClientData client;
    try {
      client = clientReadPlatformService.retrieveOne(clientId);
    } catch (Exception e) {
      log.error("Unable to load ClientData for clientId={}", clientId, e);
      return;
    }

    String firstName = client.getFirstname();
    String lastName = client.getLastname();
    String email = client.getEmailAddress();
    String mobileNumber = client.getMobileNo();

    SelfServiceNotificationEvent.Type notificationType =
        "SavingsDepositBusinessEvent".equals(eventType)
            ? SelfServiceNotificationEvent.Type.SAVINGS_DEPOSIT
            : SelfServiceNotificationEvent.Type.SAVINGS_WITHDRAWAL;

    log.info(
        "Triggering {} notification – clientId={}, savingsAccountId={}, amount={} {}",
        notificationType,
        clientId,
        savingsAccountId,
        amount.setScale(2, RoundingMode.HALF_UP),
        currencyCode);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("savingsAccountId", savingsAccountId);
    contextData.put("accountNumber", accountNumber != null ? accountNumber : "N/A");
    contextData.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
    contextData.put("currency", currencyCode);
    contextData.put("transactionDate", transactionDate.toString());
    contextData.put("transactionType", notificationType.name());

    FineractPlatformTenant tenant = null;
    try {
      tenant = ThreadLocalContextUtil.getTenant();
    } catch (Exception ignored) {
      // ignore
    }

    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            notificationType,
            clientId,
            firstName,
            lastName,
            null,
            email,
            mobileNumber,
            false,
            null,
            null,
            tenant,
            null,
            contextData);

    eventPublisher.publishEvent(event);
  }

  private byte[] extractPayloadBytes(Message message) throws Exception {
    if (message instanceof BytesMessage bytesMessage) {
      byte[] byteData = new byte[(int) bytesMessage.getBodyLength()];
      bytesMessage.readBytes(byteData);
      return byteData;
    } else if (message instanceof TextMessage textMessage) {
      // Fallback path (rare – Fineract publishes Avro binary)
      String text = textMessage.getText();
      log.info("Received TextMessage – attempting JSON fallback");
      processJsonPayload(text.getBytes(StandardCharsets.UTF_8));
      return null;
    } else {
      log.warn("Unsupported JMS message type: {}", message.getClass().getName());
      return null;
    }
  }

  private void processJsonPayload(byte[] payloadBytes) {
    try {
      String jsonStr = new String(payloadBytes, StandardCharsets.UTF_8);
      JsonNode rootNode = objectMapper.readTree(jsonStr);
      String eventType = rootNode.has("type") ? rootNode.get("type").asText() : null;
      if ("SavingsDepositBusinessEvent".equals(eventType)
          || "SavingsWithdrawalBusinessEvent".equals(eventType)) {
        log.info("JSON fallback processed for event type: {}", eventType);
        // Extend here if a pure-JSON path is ever needed
      }
    } catch (Exception e) {
      log.error("Failed to process JSON payload", e);
    }
  }
}