/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionRequest;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for interacting with the external SINPE Móvil API.
 *
 * <p>Configuration (host, headers, enabled status) is dynamically fetched from the {@code
 * c_external_service} and {@code c_external_service_properties} tables for the service named
 * 'SinpeService'.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SinpeExternalApiClient {

  private final JdbcTemplate jdbcTemplate;
  private final RestTemplate restTemplate = new RestTemplate();

  private static final String SERVICE_NAME = "SinpeService";

  /**
   * Fetches all configuration properties for the SinpeService from the database.
   *
   * @return a map of property names to their values (e.g., "host" -> "https://...")
   */
  private Map<String, String> getServiceProperties() {
    log.info("Fetching SINPE service properties from DB for serviceName={}", SERVICE_NAME);
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
        // Avoid logging secret header values at info
        if ("headerValue".equalsIgnoreCase(name)) {
          log.info("  property: {} = [REDACTED]", name);
        } else {
          log.info("  property: {} = {}", name, value);
        }
      }
    }
    log.info("Resolved property keys: {}", props.keySet());
    return props;
  }

  /** Checks if the SinpeService is enabled in the database configuration. */
  private boolean isEnabled(Map<String, String> props) {
    boolean enabled = "true".equalsIgnoreCase(props.get("isEnabled"));
    log.info("SinpeService isEnabled check: raw={}, resolved={}", props.get("isEnabled"), enabled);
    return enabled;
  }

  /** Retrieves the base host URL from the database configuration. */
  private String getHost(Map<String, String> props) {
    String host = props.getOrDefault("host", "");
    log.info("SinpeService host resolved to: '{}'", host);
    return host;
  }

  /** Builds HTTP headers, injecting the custom API key header if configured in the database. */
  private HttpHeaders buildHeaders(Map<String, String> props) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String headerName = props.get("header");
    String headerValue = props.get("headerValue");
    if (headerName != null && !headerName.isBlank() && headerValue != null) {
      headers.set(headerName, headerValue);
      log.info("Custom API header applied: name='{}' (value redacted)", headerName);
    } else {
      log.info("No custom API header configured (header={}, headerValue present={})",
          headerName, headerValue != null);
    }
    return headers;
  }

  public void createSubscription(SinpeSubscriptionRequest request) {
    log.info("createSubscription START phone={}", request != null ? request.getPhoneNumber() : null);
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping createSubscription for phone:"
              + " {}",
          request.getPhoneNumber());
      return;
    }
    String url = getHost(props) + "/subscription";
    log.info("createSubscription calling POST url={}", url);
    HttpEntity<SinpeSubscriptionRequest> entity = new HttpEntity<>(request, buildHeaders(props));
    try {
      String responseBody = restTemplate.postForObject(url, entity, String.class);
      log.info("createSubscription response body (truncated): {}", truncate(responseBody, 500));
      log.info("SINPE subscription created successfully for phone: {}", request.getPhoneNumber());
    } catch (Exception e) {
      log.error("Failed to create SINPE subscription for phone: {}", request.getPhoneNumber(), e);
      throw new RuntimeException("Failed to create SINPE subscription: " + e.getMessage(), e);
    }
    log.info("createSubscription END phone={}", request.getPhoneNumber());
  }

  public void editSubscription(SinpeSubscriptionEditRequest request) {
    log.info("editSubscription START phone={}", request != null ? request.getPhoneNumber() : null);
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping editSubscription for phone: {}",
          request.getPhoneNumber());
      return;
    }
    String url = getHost(props) + "/subscription/edit";
    log.info("editSubscription calling POST url={}", url);
    HttpEntity<SinpeSubscriptionEditRequest> entity =
        new HttpEntity<>(request, buildHeaders(props));
    try {
      String responseBody = restTemplate.postForObject(url, entity, String.class);
      log.info("editSubscription response body (truncated): {}", truncate(responseBody, 500));
      log.info("SINPE subscription edited successfully for phone: {}", request.getPhoneNumber());
    } catch (Exception e) {
      log.error("Failed to edit SINPE subscription for phone: {}", request.getPhoneNumber(), e);
      throw new RuntimeException("Failed to edit SINPE subscription: " + e.getMessage(), e);
    }
    log.info("editSubscription END phone={}", request.getPhoneNumber());
  }

  public void deleteSubscription(String phoneNumber) {
    log.info("deleteSubscription START phone={}", phoneNumber);
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping deleteSubscription for phone:"
              + " {}",
          phoneNumber);
      return;
    }
    String url = getHost(props) + "/subscription/" + phoneNumber;
    log.info("deleteSubscription calling DELETE url={}", url);
    HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(props));
    try {
      ResponseEntity<String> response =
          restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
      log.info(
          "deleteSubscription HTTP status={}, body (truncated): {}",
          response.getStatusCode(),
          truncate(response.getBody(), 500));
      log.info("SINPE subscription deleted successfully for phone: {}", phoneNumber);
    } catch (Exception e) {
      log.error("Failed to delete SINPE subscription for phone: {}", phoneNumber, e);
      throw new RuntimeException("Failed to delete SINPE subscription: " + e.getMessage(), e);
    }
    log.info("deleteSubscription END phone={}", phoneNumber);
  }

  public String transferToPhone(SinpeTransferRequest request) {
    log.info(
        "transferToPhone START destinationPhone={}",
        request != null ? request.getDestinationPhone() : null);
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping transferToPhone for phone: {}",
          request.getDestinationPhone());
      return null;
    }
    String url = getHost(props) + "/transfer/account-to-phone";
    log.info("transferToPhone calling POST url={}", url);
    HttpEntity<SinpeTransferRequest> entity = new HttpEntity<>(request, buildHeaders(props));
    try {
      String response = restTemplate.postForObject(url, entity, String.class);
      log.info("transferToPhone response body (truncated): {}", truncate(response, 500));
      log.info("SINPE transfer to phone {} processed successfully", request.getDestinationPhone());
      log.info("transferToPhone END destinationPhone={}", request.getDestinationPhone());
      return response;
    } catch (Exception e) {
      log.error("Failed to process SINPE transfer to phone: {}", request.getDestinationPhone(), e);
      throw new RuntimeException("Failed to process SINPE transfer: " + e.getMessage(), e);
    }
  }

  // =====================================================================
  // NUEVO MÉTODO: OBTENER DETALLE DE LA TRANSACCIÓN SINPE
  // =====================================================================

  /**
   * Consult details of a SINPE transaction using the receipt or reference number.
   *
   * @param referenceNumber The receipt number (e.g. 1162026072637383000000001161)
   * @return String JSON response from the external SINPE API
   */
  public String getTransactionDetail(String referenceNumber) {
    log.info("getTransactionDetail START referenceNumber={}", referenceNumber);
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping getTransactionDetail for ref:"
              + " {}",
          referenceNumber);
      return null;
    }
    String url = getHost(props) + "/transfer/data/" + referenceNumber;
    log.info("getTransactionDetail calling GET url={}", url);
    HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(props));
    try {
      ResponseEntity<String> response =
          restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
      log.info(
          "getTransactionDetail HTTP status={}, body (truncated): {}",
          response.getStatusCode(),
          truncate(response.getBody(), 500));
      log.info("SINPE transaction details fetched successfully for ref: {}", referenceNumber);
      log.info("getTransactionDetail END referenceNumber={}", referenceNumber);
      return response.getBody();
    } catch (Exception e) {
      log.error("Failed to fetch SINPE transaction details for ref: {}", referenceNumber, e);
      throw new RuntimeException("Failed to fetch SINPE transaction details: " + e.getMessage(), e);
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