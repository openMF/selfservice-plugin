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
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "fineract.events.external.producer.jms.enabled",
    havingValue = "true",
    matchIfMissing = false)
@ConditionalOnBean(name = "topicJmsListenerContainerFactory")
public class SelfServiceSavingsEventNotificationListener {

  private final ApplicationEventPublisher eventPublisher;
  private final ClientReadPlatformService clientReadPlatformService;
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  private final TenantDetailsService tenantDetailsService;
  private final ObjectMapper objectMapper;

  /**
   * Destination must match the topic that Fineract’s JMS producer publishes to.
   * Official property: fineract.events.external.producer.jms.event-topic-name
   */
  @Value("${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}")
  private String eventTopicName;

  @JmsListener(
      destination = "${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}",
      containerFactory = "topicJmsListenerContainerFactory",
      subscription = "${fineract.external.events.jms.subscription-name:selfservice-savings-notifications}")
  public void onMessage(Message message) {
    FineractPlatformTenant originalTenant = null;
    try {
      originalTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
      // expected on background JMS threads
    }

    try {
      byte[] payloadBytes = extractPayloadBytes(message);
      if (payloadBytes == null || payloadBytes.length == 0) {
        log.warn("Received null or empty payload from JMS message");
        return;
      }

      log.info(
          "JMS message received on topic '{}' - Type: {}, Length: {}",
          eventTopicName,
          message.getClass().getSimpleName(),
          payloadBytes.length);

      ByteBuffer byteBuffer = ByteBuffer.wrap(payloadBytes);
      MessageV1 messageV1 = MessageV1.fromByteBuffer(byteBuffer);

      String eventType = messageV1.getType() != null ? messageV1.getType().toString() : null;
      String tenantIdentifier =
          messageV1.getTenantId() != null ? messageV1.getTenantId().toString() : "default";

      log.info(
          "Parsed Avro MessageV1. Event type: {}, Category: {}, Tenant: {}",
          eventType,
          messageV1.getCategory(),
          tenantIdentifier);

      FineractPlatformTenant tenant = resolveTenant(tenantIdentifier);
      if (tenant == null) {
        log.error("Could not resolve any valid tenant for identifier '{}'. Aborting.", tenantIdentifier);
        return;
      }

      ThreadLocalContextUtil.setTenant(tenant);
      HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
      businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
      ThreadLocalContextUtil.setBusinessDates(businessDates);

      if ("SavingsDepositBusinessEvent".equals(eventType)
          || "SavingsWithdrawalBusinessEvent".equals(eventType)) {
        ByteBuffer dataBuffer = messageV1.getData();
        if (dataBuffer == null) {
          log.warn("Event envelope has no data payload");
          return;
        }
        SavingsAccountTransactionDataV1 txnData =
            SavingsAccountTransactionDataV1.fromByteBuffer(dataBuffer);
        processSavingsTransactionEvent(txnData, eventType);
      } else {
        log.debug("Ignoring unsupported external event type: {}", eventType);
      }
    } catch (Exception e) {
      log.error("Failed to parse or process external event message", e);
      // rethrow so the transacted session rolls back and the message is redelivered
      throw new RuntimeException("Failed to process external event", e);
    } finally {
      ThreadLocalContextUtil.reset();
      if (originalTenant != null) {
        ThreadLocalContextUtil.setTenant(originalTenant);
      }
    }
  }

  private FineractPlatformTenant resolveTenant(String source) {
    try {
      return tenantDetailsService.loadTenantById(source);
    } catch (Exception e) {
      log.warn("Failed to load tenant '{}': {}", source, e.getMessage());
    }
    if (!"default".equals(source)) {
      try {
        log.info("Falling back to 'default' tenant");
        return tenantDetailsService.loadTenantById("default");
      } catch (Exception e) {
        log.error("Failed to load fallback 'default' tenant: {}", e.getMessage());
      }
    }
    return null;
  }

  private void processSavingsTransactionEvent(
      SavingsAccountTransactionDataV1 txnRecord, String eventType) {
    try {
      Long savingsAccountId = txnRecord.getAccountId();
      String accountNumber =
          txnRecord.getAccountNo() != null ? txnRecord.getAccountNo().toString() : null;
      BigDecimal amount = txnRecord.getAmount() != null ? txnRecord.getAmount() : BigDecimal.ZERO;

      String currencyCode = "USD";
      if (txnRecord.getCurrency() != null && txnRecord.getCurrency().getCode() != null) {
        currencyCode = txnRecord.getCurrency().getCode().toString();
      }

      LocalDate transactionDate = LocalDate.now();
      if (txnRecord.getDate() != null) {
        try {
          transactionDate = LocalDate.parse(txnRecord.getDate().toString());
        } catch (Exception e) {
          log.warn("Could not parse date string: {}", txnRecord.getDate());
        }
      }

      Long clientId = null;
      if (savingsAccountId != null) {
        try {
          SavingsAccountData accountData =
              savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
          clientId = accountData.getClientId();
          if (accountNumber == null) {
            accountNumber = accountData.getAccountNo();
          }
          log.info(
              "Retrieved savings account {} for client {}", savingsAccountId, clientId);
        } catch (Exception e) {
          log.error(
              "Could not fetch savings account data for accountId: {}", savingsAccountId, e);
        }
      }

      triggerNotification(
          clientId, savingsAccountId, accountNumber, amount, currencyCode, transactionDate, eventType);
    } catch (Exception e) {
      log.error("Failed to process savings transaction event", e);
      throw e; // let the outer handler roll back the JMS transaction
    }
  }

  private void triggerNotification(
      Long clientId,
      Long savingsAccountId,
      String accountNumber,
      BigDecimal amount,
      String currencyCode,
      LocalDate transactionDate,
      String eventType) {

    if (clientId == null || savingsAccountId == null) {
      log.warn(
          "Incomplete payload. ClientId: {}, SavingsAccountId: {}", clientId, savingsAccountId);
      return;
    }

    ClientData clientData = null;
    try {
      clientData = clientReadPlatformService.retrieveOne(clientId);
    } catch (Exception e) {
      log.warn("Could not fetch client data for clientId: {}", clientId, e);
    }

    String firstName = clientData != null ? clientData.getFirstname() : null;
    String lastName = clientData != null ? clientData.getLastname() : null;
    String email = clientData != null ? clientData.getEmailAddress() : null;
    String mobileNumber = clientData != null ? clientData.getMobileNo() : null;

    String transactionType = eventType.contains("Deposit") ? "DEPOSIT" : "WITHDRAWAL";
    SelfServiceNotificationEvent.Type notificationType =
        eventType.contains("Deposit")
            ? SelfServiceNotificationEvent.Type.SAVINGS_DEPOSIT
            : SelfServiceNotificationEvent.Type.SAVINGS_WITHDRAWAL;

    log.info(
        "Triggering {} notification for Client: {}, Savings Account: {}, Amount: {} {}",
        transactionType,
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
    contextData.put("transactionType", transactionType);

    FineractPlatformTenant tenant = null;
    try {
      tenant = ThreadLocalContextUtil.getTenant();
    } catch (Exception ignored) {
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
      String text = textMessage.getText();
      log.info("Received TextMessage – attempting JSON fallback parsing");
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
        log.info("JSON fallback for event type: {}", eventType);
      }
    } catch (Exception e) {
      log.error("Failed to process JSON payload", e);
    }
  }
}