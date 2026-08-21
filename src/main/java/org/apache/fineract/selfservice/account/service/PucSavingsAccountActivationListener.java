/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.avro.MessageV1;
import org.apache.fineract.avro.savings.v1.SavingsAccountDataV1;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.selfservice.account.data.PucAddAccountRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Durable JMS topic listener that consumes Fineract external business events
 * ({@code SavingsActivateBusinessEvent}) and automatically triggers account
 * registration in PUC / KINDO.
 *
 * <p>Fully multi-tenant: restores the correct {@link FineractPlatformTenant} from the Avro
 * envelope before any database access and always clears the {@code ThreadLocal} afterwards.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PucSavingsAccountActivationListener {

    private final PucExternalApiClient pucExternalApiClient;
    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final ClientReadPlatformService clientReadPlatformService;
    private final TenantDetailsService tenantDetailsService;
    private final ObjectMapper objectMapper;

    /**
     * Destination = the topic name published by Fineract core.
     * Official property: {@code fineract.events.external.producer.jms.event-topic-name}
     */
    @Value("${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}")
    private String eventTopicName;

    /**
     * Durable subscription name for PUC activation.
     */
    @Value("${fineract.external.events.jms.puc-subscription-name:selfservice-puc-activation-notifications}")
    private String subscriptionName;

    @PostConstruct
    public void init() {
        log.info("SelfServicePucSavingsAccountActivationListener STARTED");
        log.info("  destination     = {}", eventTopicName);
        log.info("  subscription    = {}", subscriptionName);
        log.info("  containerFactory= topicJmsListenerContainerFactory");
    }

    @JmsListener(
            destination =
                    "${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}",
            containerFactory = "topicJmsListenerContainerFactory",
            subscription =
                    "${fineract.external.events.jms.puc-subscription-name:selfservice-puc-activation-notifications}",
            id = "selfServicePucSavingsActivationListener")
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
            HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
            businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
            ThreadLocalContextUtil.setBusinessDates(businessDates);

            // Process only the activation event
            if ("SavingsActivateBusinessEvent".equals(eventType)) {

                ByteBuffer dataBuffer = messageV1.getData();
                if (dataBuffer == null) {
                    log.warn("Event envelope has no data payload for type {}", eventType);
                    return;
                }

                SavingsAccountDataV1 accountDataV1 = SavingsAccountDataV1.fromByteBuffer(dataBuffer);
                processSavingsActivationEvent(accountDataV1);
            } else {
                log.debug("Ignoring external event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Failed to parse or process external PUC activation event message", e);
            throw new RuntimeException("JMS processing failed – triggering redelivery", e);
        } finally {
            // Clean ThreadLocal
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

    private void processSavingsActivationEvent(SavingsAccountDataV1 accountDataV1) {
        Long savingsAccountId = null;

        if (accountDataV1.getId() != null) {
            try {
                savingsAccountId = Long.valueOf(accountDataV1.getId().toString());
            } catch (NumberFormatException e) {
                log.error("No se pudo parsear el ID de la cuenta desde Avro: {}", accountDataV1.getId());
                return;
            }
        }

        if (savingsAccountId == null) {
            log.warn("No savingsAccountId present in SavingsAccountDataV1 payload");
            return;
        }

        log.info("Evento capturado: [SavingsActivateBusinessEvent]. Iniciando registro automático de cuenta en el PUC para ID: {}", savingsAccountId);

        try {
            // 1. Consultar la información de la cuenta en Fineract
            SavingsAccountData accountData = this.savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
            if (accountData == null) {
                log.warn("No se encontró la cuenta de ahorro con ID: {}", savingsAccountId);
                return;
            }

            // 2. Consultar la información del Cliente para obtener la Identificación/Cédula
            ClientData clientData = this.clientReadPlatformService.retrieveOne(accountData.getClientId());
            if (clientData == null) {
                log.warn("No se encontraron datos del cliente con ID: {}", accountData.getClientId());
                return;
            }

            // 3. Construir la solicitud para el PUC / KINDO
            PucAddAccountRequest pucRequest = new PucAddAccountRequest();

            // IBAN (externalId de la cuenta) -> "CR19037300220010000086"
            String iban = extractExternalIdValue(accountData.getExternalId());
            if (iban.isBlank()) {
                iban = accountData.getAccountNo();
            }

            // Account Number
            String accountNumber = (accountData.getAccountNo() != null) ? accountData.getAccountNo() : iban;

            // Holder Name
            String holder = (accountData.getClientName() != null && !accountData.getClientName().isBlank())
                    ? accountData.getClientName()
                    : clientData.getDisplayName();

            // Holder ID (Cédula/Identificación)
            String holderId = extractExternalIdValue(clientData.getExternalId());

            // Holder ID Type (FISICA o JURIDICA)
            String holderIdType = resolveHolderIdType(clientData);

            // Currency Code
            String currencyCode = (accountData.getCurrency() != null && accountData.getCurrency().getCode() != null)
                    ? accountData.getCurrency().getCode()
                    : "CRC";

            // Seteo de propiedades en el DTO del PUC
            pucRequest.setAccountNumber(iban);
            pucRequest.setHolder(holder);
            pucRequest.setHolderId(holderId);
            pucRequest.setHolderIdType(holderIdType);
            pucRequest.setCurrencyCode(currencyCode);
            pucRequest.setAccountType("CAR");

            log.info("Enviando AddAccount a PUC -> IBAN: {}, AccountNo: {}, Titular: {}, Cédula: {}, Tipo Cédula: {}, Moneda: {}, Tipo: CAR",
                    iban, accountNumber, holder, holderId, holderIdType, currencyCode);

            // 4. Enviar la petición al cliente REST de PUC
            String response = this.pucExternalApiClient.addAccount(pucRequest);
            log.info("Respuesta del servicio PUC para IBAN {}: {}", iban, response);

        } catch (Exception e) {
            log.error("Error procesando el registro en PUC tras evento de activación (SavingsActivateBusinessEvent)", e);
        }
    }

    /**
     * Determina el tipo de identificación (FISICA o JURIDICA) según la forma legal del cliente
     */
    private String resolveHolderIdType(ClientData clientData) {
        if (clientData == null || clientData.getLegalForm() == null) {
            return "FISICA";
        }

        Long legalFormId = clientData.getLegalForm().getId();
        if (legalFormId != null && legalFormId == 2L) {
            return "JURIDICA";
        }

        String value = clientData.getLegalForm().getValue();
        if (value != null && "Entity".equalsIgnoreCase(value.trim())) {
            return "JURIDICA";
        }

        return "FISICA";
    }

    /**
     * Extrae de forma segura el valor en String de un objeto ExternalId o String.
     */
    private String extractExternalIdValue(Object externalIdObj) {
        if (externalIdObj == null) {
            return "";
        }
        if (externalIdObj instanceof ExternalId extId) {
            return extId.isEmpty() ? "" : extId.getValue();
        }
        if (externalIdObj instanceof String str) {
            return str;
        }
        return externalIdObj.toString();
    }

    private byte[] extractPayloadBytes(Message message) throws Exception {
        if (message instanceof BytesMessage bytesMessage) {
            byte[] byteData = new byte[(int) bytesMessage.getBodyLength()];
            bytesMessage.readBytes(byteData);
            return byteData;
        } else if (message instanceof TextMessage textMessage) {
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
            if ("SavingsActivateBusinessEvent".equals(eventType)) {
                log.info("JSON fallback processed for event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process JSON payload", e);
        }
    }
}