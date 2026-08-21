package org.apache.fineract.selfservice.account.service;

/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.selfservice.account.data.PucAddAccountRequest;
import org.apache.fineract.selfservice.account.data.PucUpdateAccountStateRequest;
import org.apache.fineract.selfservice.account.domain.SelfServicePucAddAccountAudit;
import org.apache.fineract.selfservice.account.domain.SelfServicePucAddAccountAuditRepository;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for interacting with the external KINDO / PUC API.
 *
 * <p>Configuration (host, headers, enabled status) is dynamically fetched from the {@code
 * c_external_service} and {@code c_external_service_properties} tables for the service named
 * 'PucService'.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PucExternalApiClient {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final SelfServicePucAddAccountAuditRepository pucAddAccountAuditRepository;

    // Nombre del servicio registrado en c_external_service
    private static final String SERVICE_NAME = "PucService";

    // ObjectMapper configurado para ignorar campos nulos al serializar hacia KINDO/PUC
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * Fetches configuration properties for PucService from c_external_service_properties.
     */
    private Map<String, String> getServiceProperties() {
        log.info("Fetching PUC service properties from DB for serviceName={}", SERVICE_NAME);
        Map<String, String> props = new HashMap<>();
        String sql =
                "SELECT p.name, p.value FROM c_external_service_properties p "
                        + "INNER JOIN c_external_service s ON p.external_service_id = s.id "
                        + "WHERE s.name = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, SERVICE_NAME);
        log.info("Loaded {} property row(s) for {}", rows.size(), SERVICE_NAME);
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("name");
            String value = (String) row.get("value");
            if (name != null && value != null) {
                props.put(name, value);
                if ("headerValue".equalsIgnoreCase(name)) {
                    log.info("  property: {} = [REDACTED]", name);
                } else {
                    log.info("  property: {} = {}", name, value);
                }
            }
        }
        return props;
    }

    /** Checks if PucService is enabled in DB configuration. */
    private boolean isEnabled(Map<String, String> props) {
        boolean enabled = "true".equalsIgnoreCase(props.get("isEnabled"));
        log.info("PucService isEnabled check: raw={}, resolved={}", props.get("isEnabled"), enabled);
        return enabled;
    }

    /** Retrieves base host URL for PucService. */
    private String getHost(Map<String, String> props) {
        String host = props.getOrDefault("host", "");
        log.info("PucService host resolved to: '{}'", host);
        return host;
    }

    /** Builds HTTP headers with JSON content-type and custom security header. */
    private HttpHeaders buildHeaders(Map<String, String> props) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String headerName = props.get("header");
        String headerValue = props.get("headerValue");
        if (headerName != null && !headerName.isBlank() && headerValue != null) {
            headers.set(headerName, headerValue);
            log.info("Custom API header applied: name='{}' (value redacted)", headerName);
        } else {
            log.info("No custom API header configured");
        }
        return headers;
    }

    /**
     * ResBase AddAccount (ReqAddAccount AccountData)
     * Permite enviar a registrar una cuenta al PUC.
     */
    public String addAccount(PucAddAccountRequest request) {
        if (request == null) {
            log.warn("PucExternalApiClient.addAccount: Request payload is null. Skipping.");
            return null;
        }

        String iban = request.getAccountNumber();
        log.info("PucExternalApiClient.addAccount START IBAN={}", iban);

        Map<String, String> props = getServiceProperties();
        if (!isEnabled(props)) {
            log.warn("PucService is disabled in c_external_service. Skipping addAccount for IBAN={}", iban);
            saveAddAccountAudit(request, null, null, false, "PucService is disabled in DB configuration");
            return null;
        }

        String url = getHost(props) + "/addAccount";
        log.info("PucExternalApiClient.addAccount calling POST url={}", url);

        Long clientId = resolveClientIdByHolderId(request.getHolderId());

        try {
            String jsonPayload = OBJECT_MAPPER.writeValueAsString(request);
            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, buildHeaders(props));

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();

            log.info("AddAccount response body (truncated): {}", truncate(responseBody, 500));

            boolean isSuccessful = false;
            String operationId = null;
            String rejectDescription = null;

            if (responseBody != null) {
                JsonNode jsonNode = OBJECT_MAPPER.readTree(responseBody);
                isSuccessful = jsonNode.path("successful").asBoolean(jsonNode.path("IsSuccessful").asBoolean(false));
                operationId = jsonNode.path("operationId").asText(jsonNode.path("OperationId").asText(null));

                if (!isSuccessful) {
                    JsonNode errorsNode = jsonNode.path("Errors");
                    if (errorsNode.isArray() && !errorsNode.isEmpty()) {
                        rejectDescription = errorsNode.get(0).asText();
                    } else {
                        rejectDescription = jsonNode.path("message").asText("Error in PUC execution");
                    }
                }
            }

            saveAddAccountAudit(request, clientId, operationId, isSuccessful, rejectDescription);
            log.info("PucExternalApiClient.addAccount END IBAN={}", iban);
            return responseBody;

        } catch (Exception e) {
            log.error("Failed to execute AddAccount in PUC for IBAN={}", iban, e);
            saveAddAccountAudit(request, clientId, null, false, e.getMessage());
            throw new RuntimeException("Error invoking AddAccount in PUC for IBAN " + iban + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resuelve el client_id de m_client a partir del holderId (external_id)
     */
    private Long resolveClientIdByHolderId(String holderId) {
        if (holderId == null || holderId.isBlank()) return null;
        try {
            String sql = "SELECT id FROM m_client WHERE external_id = ? LIMIT 1";
            List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, holderId.trim());
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("Error resolving client_id for holderId={}: {}", holderId, e.getMessage());
            return null;
        }
    }

    /**
     * Persiste la auditoria en m_puc_add_account_audit usando JPA Repository
     */
    private void saveAddAccountAudit(
            PucAddAccountRequest request,
            Long clientId,
            String operationId,
            boolean successful,
            String rejectDescription) {
        try {
            SelfServicePucAddAccountAudit audit =
                    SelfServicePucAddAccountAudit.builder()
                            .clientId(clientId)
                            .accountNumber(request.getAccountNumber())
                            .accountType(request.getAccountType())
                            .holderId(request.getHolderId())
                            .holderIdType(request.getHolderIdType())
                            .holder(request.getHolder())
                            .currencyCode(request.getCurrencyCode())
                            .ipNumber(request.getIpNumber())
                            .operationId(operationId)
                            .successful(successful)
                            .rejectDescription(truncate(rejectDescription, 500))
                            .createdOnUtc(OffsetDateTime.now(ZoneOffset.UTC))
                            .build();

            pucAddAccountAuditRepository.saveAndFlush(audit);
            log.info("Audit entry persisted in m_puc_add_account_audit for IBAN={}", request.getAccountNumber());
        } catch (Exception e) {
            log.error("Error persisting audit log for IBAN={}: {}", request.getAccountNumber(), e.getMessage());
        }
    }

    /**
     * ResBase UpdateAccount (ReqUpdateAccount AccountData)
     * Permite actualizar diferentes propiedades de una cuenta en el PUC.
     */
    public String updateAccount(PucUpdateAccountStateRequest request) {
        if (request == null) {
            log.warn("PucExternalApiClient.updateAccount: Request payload is null. Skipping.");
            return null;
        }

        String iban = request.getAccountNumber();
        log.info("PucExternalApiClient.updateAccount START IBAN={}", iban);

        Map<String, String> props = getServiceProperties();
        if (!isEnabled(props)) {
            log.warn("PucService is disabled in c_external_service. Skipping updateAccount for IBAN={}", iban);
            return null;
        }

        String url = getHost(props) + "/UpdateAccount";
        log.info("PucExternalApiClient.updateAccount calling POST url={}", url);

        try {
            String jsonPayload = OBJECT_MAPPER.writeValueAsString(request);
            log.info("UpdateAccount Request Payload: {}", jsonPayload);

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, buildHeaders(props));

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            log.info("UpdateAccount response body (truncated): {}", truncate(response.getBody(), 500));
            log.info("PucExternalApiClient.updateAccount END IBAN={}", iban);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to execute UpdateAccount in PUC for IBAN={}", iban, e);
            throw new RuntimeException("Error invoking UpdateAccount in PUC for IBAN " + iban + ": " + e.getMessage(), e);
        }
    }

    /**
     * ResAccountByIBAN GetAccountByIBAN (ReqAccountByIBAN AccountNumber)
     * Permite consultar en el PUC los datos de una cuenta utilizando como criterio de búsqueda el IBAN.
     */
    public String getAccountByIBAN(String ibanNumber) {
        log.info("PucExternalApiClient.getAccountByIBAN START IBAN={}", ibanNumber);

        Map<String, String> props = getServiceProperties();
        if (!isEnabled(props)) {
            log.warn("PucService is disabled in c_external_service. Skipping getAccountByIBAN for IBAN={}", ibanNumber);
            return null;
        }

        String url = getHost(props) + "/GetAccountByIBAN";
        log.info("PucExternalApiClient.getAccountByIBAN calling POST url={}", url);

        try {
            Map<String, String> requestBody = Map.of("AccountNumber", ibanNumber);
            String jsonPayload = OBJECT_MAPPER.writeValueAsString(requestBody);

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, buildHeaders(props));
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            log.info("GetAccountByIBAN HTTP status={}, body (truncated): {}",
                    response.getStatusCode(),
                    truncate(response.getBody(), 500));
            log.info("PucExternalApiClient.getAccountByIBAN END IBAN={}", ibanNumber);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch GetAccountByIBAN from PUC for IBAN={}", ibanNumber, e);
            throw new RuntimeException("Error invoking GetAccountByIBAN in PUC for IBAN " + ibanNumber + ": " + e.getMessage(), e);
        }
    }

    /** Truncate long response bodies so info logs stay readable. */
    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...[truncated, totalLen=" + value.length() + "]";
    }
}