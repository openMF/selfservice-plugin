/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.PaymentLinkRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkResponse;
import org.apache.fineract.selfservice.account.domain.SelfServicePaymentLink;
import org.apache.fineract.selfservice.account.domain.SelfServicePaymentLinkRepository;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLinkExternalService {

  private static final String SERVICE_NAME = "PaymentLinkService";

  private final JdbcTemplate jdbcTemplate;
  private final SelfServicePaymentLinkRepository paymentLinkRepository;
  private final PlatformSelfServiceSecurityContext securityContext;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate();

  @Transactional
  public PaymentLinkResponse createPaymentLink(PaymentLinkRequest request) {
    AppSelfServiceUser selfUser = securityContext.authenticatedSelfServiceUser();
    Long userId = selfUser.getId();

    if (selfUser.getAppUserClientMappings() == null
        || selfUser.getAppUserClientMappings().isEmpty()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.payment.link.no.client.mapping",
          "Authenticated self-service user has no linked client");
    }

    Client client = selfUser.getAppUserClientMappings().iterator().next().getClient();
    Long clientId = client.getId();

    // Basic ownership / permission guard (extend as needed for savings account ownership)
    if (request.getCustomerAccount() == null
        || request.getAmount() == null
        || request.getAmount().signum() <= 0) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.payment.link.invalid.request",
          "customerAccount and a positive amount are required");
    }

    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.payment.link.service.disabled",
          "PaymentLinkService is disabled in configuration");
    }

    String host = getHost(props);
    if (host == null || host.isBlank()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.payment.link.host.missing", "PaymentLinkService host is not configured");
    }

    String url = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;

    HttpHeaders headers = buildHeaders(props);
    HttpEntity<PaymentLinkRequest> entity = new HttpEntity<>(request, headers);

    try {
      log.info("Calling external PaymentLinkService: {} payload={}", url, request);
      ResponseEntity<String> response =
          restTemplate.postForEntity(URI.create(url), entity, String.class);

      String body = response.getBody();
      log.info("PaymentLinkService response: {}", body);

      JsonNode node = objectMapper.readTree(body);

      PaymentLinkResponse result =
          PaymentLinkResponse.builder()
              .checkoutId(text(node, "checkoutId"))
              .paymentUrl(text(node, "paymentUrl"))
              .paymentStatus(text(node, "paymentStatus"))
              .success(node.path("success").asBoolean(false))
              .build();

      // Persist audit record
      SelfServicePaymentLink entityToSave = new SelfServicePaymentLink();
      entityToSave.setAppSelfServiceUserId(userId);
      entityToSave.setClientId(clientId);
      entityToSave.setSavingsAccountId(request.getCustomerAccount());
      entityToSave.setCheckoutId(result.getCheckoutId());
      entityToSave.setPaymentUrl(result.getPaymentUrl());
      entityToSave.setPaymentStatus(result.getPaymentStatus());
      entityToSave.setCustomerName(request.getCustomerName());
      entityToSave.setCustomerEmail(request.getCustomerEmail());
      entityToSave.setCustomerPhone(request.getCustomerPhone());
      entityToSave.setAmount(request.getAmount());
      entityToSave.setCurrency(request.getCurrency());
      entityToSave.setDescription(request.getDescription());
      entityToSave.setSuccess(result.isSuccess());
      entityToSave.setExternalResponse(body);

      paymentLinkRepository.save(entityToSave);

      return result;
    } catch (Exception e) {
      log.error("Failed to create payment link via external service", e);
      throw new GeneralPlatformDomainRuleException(
          "error.msg.payment.link.external.failure",
          "External payment link creation failed: " + e.getMessage());
    }
  }

  private Map<String, String> getServiceProperties() {
    Map<String, String> props = new HashMap<>();
    String sql =
        """
                SELECT p.name, p.value
                FROM c_external_service_properties p
                INNER JOIN c_external_service s ON p.external_service_id = s.id
                WHERE s.name = ?
                """;
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, SERVICE_NAME);
    for (Map<String, Object> row : rows) {
      String name = (String) row.get("name");
      String value = (String) row.get("value");
      if (name != null && value != null) {
        props.put(name, value);
      }
    }
    return props;
  }

  private boolean isEnabled(Map<String, String> props) {
    return "true".equalsIgnoreCase(props.get("isEnabled"));
  }

  private String getHost(Map<String, String> props) {
    return props.getOrDefault("host", "");
  }

  private HttpHeaders buildHeaders(Map<String, String> props) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));

    String headerName = props.get("header");
    String headerValue = props.get("headerValue");
    if (headerName != null && !headerName.isBlank() && headerValue != null) {
      headers.set(headerName, headerValue);
    }
    return headers;
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.path(field);
    return n.isMissingNode() || n.isNull() ? null : n.asText();
  }
}
