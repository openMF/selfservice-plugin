/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class RemittanceExternalService {

  private final JdbcTemplate jdbcTemplate;
  private final RestTemplate restTemplate = new RestTemplate();

  private static final String SERVICE_NAME = "RemittanceService";

  private Map<String, String> getServiceProperties() {
    Map<String, String> props = new HashMap<>();
    String sql =
        "SELECT p.name, p.value FROM c_external_service_properties p "
            + "INNER JOIN c_external_service s ON p.external_service_id = s.id "
            + "WHERE s.name = ?";
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

  private String getDefaultVendor(Map<String, String> props) {
    return props.getOrDefault("defaultVendor", "RIA");
  }

  private HttpHeaders buildHeaders(Map<String, String> props) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

    String headerName = props.get("header");
    String headerValue = props.get("headerValue");
    if (headerName != null && headerValue != null && !headerValue.isBlank()) {
      headers.set(headerName, headerValue);
    }
    // Tenant context for multi-tenancy
    String tenantId = ThreadLocalContextUtil.getTenant() != null
        ? ThreadLocalContextUtil.getTenant().getTenantIdentifier()
        : "default";
    headers.set("tenant", tenantId);
    return headers;
  }

  private String getTenantIdentifier() {
    return ThreadLocalContextUtil.getTenant() != null
        ? ThreadLocalContextUtil.getTenant().getTenantIdentifier()
        : "default";
  }

  public String getAvailableVendors(String operationType) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn("RemittanceService is disabled. Skipping getAvailableVendors.");
      return "{\"vendors\":[]}";
    }
    String url = getHost(props) + "/" + getTenantIdentifier() + "/vendors";
    HttpHeaders headers = buildHeaders(props);
    if (operationType != null) {
      headers.set("X-Operation-Type", operationType);
    }
    return executeGetRequest(url, headers);
  }

  public String getProducts(String vendor, String acceptLanguage) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"products\":[]}";
    }
    String url = getHost(props) + "/products";
    HttpHeaders headers = buildHeaders(props);
    headers.set("vendor", vendor != null ? vendor : getDefaultVendor(props));
    if (acceptLanguage != null) {
      headers.set("Accept-Language", acceptLanguage);
    }
    return executeGetRequest(url, headers);
  }

  public String getCountries(String vendor, String acceptLanguage) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"countries\":[]}";
    }
    String url = getHost(props) + "/countries";
    HttpHeaders headers = buildHeaders(props);
    headers.set("vendor", vendor != null ? vendor : getDefaultVendor(props));
    if (acceptLanguage != null) {
      headers.set("Accept-Language", acceptLanguage);
    }
    return executeGetRequest(url, headers);
  }

  public String getDeliveryMethods(String vendor, String countryAbbrev, String productId, String acceptLanguage) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"deliveryMethods\":[]}";
    }
    String url = getHost(props) + "/countries/deliverymethods";
    HttpHeaders headers = buildHeaders(props);
    headers.set("vendor", vendor != null ? vendor : getDefaultVendor(props));
    if (countryAbbrev != null) {
      headers.set("abbrev", countryAbbrev);
    }
    if (productId != null) {
      headers.set("Product-Id", productId);
    }
    if (acceptLanguage != null) {
      headers.set("Accept-Language", acceptLanguage);
    }
    return executeGetRequest(url, headers);
  }

  public String findTransaction(String vendor, String id) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\"}";
    }
    String v = vendor != null ? vendor : getDefaultVendor(props);
    String url = getHost(props) + "/" + getTenantIdentifier() + "/" + v + "/transactions/" + id;
    return executeGetRequest(url, buildHeaders(props));
  }

  public String validateRecipient(String vendor, String transactionId, Object recipientBody) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\"}";
    }
    String v = vendor != null ? vendor : getDefaultVendor(props);
    String url = getHost(props) + "/" + getTenantIdentifier() + "/" + v + "/transactions/"
        + transactionId + "/recipient";
    return executePostRequest(url, recipientBody, buildHeaders(props));
  }

  public String assignPayout(String vendor, String transactionId, Object body) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\"}";
    }
    String v = vendor != null ? vendor : getDefaultVendor(props);
    String url = getHost(props) + "/" + getTenantIdentifier() + "/" + v + "/transactions/"
        + transactionId + "/payout-assignment";
    return executePostRequest(url, body, buildHeaders(props));
  }

  public String confirmPayout(String vendor, String transactionId, String clientIdMifos, Object body) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\"}";
    }
    String v = vendor != null ? vendor : getDefaultVendor(props);
    String url = getHost(props) + "/" + getTenantIdentifier() + "/" + v + "/transactions/"
        + transactionId + "/payout-confirmation/" + clientIdMifos;
    return executePostRequest(url, body, buildHeaders(props));
  }

  public String unassignPayout(String vendor, String transactionId, Object body) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\"}";
    }
    // Note: Postman uses /v1/remittances path in some places; adapt to consistent tenant/vendor style
    String v = vendor != null ? vendor : getDefaultVendor(props);
    String url = getHost(props) + "/v1/remittances/" + getTenantIdentifier() + "/transactions/"
        + transactionId + "/payout-assignment";
    return executeDeleteRequest(url, body, buildHeaders(props));
  }

  public String rejectPayout(String vendor, String transactionId, Object body) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\"}";
    }
    String url = getHost(props) + "/api/transactions/" + transactionId + "/payout-rejection";
    return executePostRequest(url, body, buildHeaders(props));
  }

  public String createRemittance(String vendor, Object body) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      return "{\"status\":\"disabled\",\"message\":\"RemittanceService is disabled\"}";
    }
    String v = vendor != null ? vendor : getDefaultVendor(props);
    // Assuming create endpoint follows pattern; adjust if MS has specific create path
    String url = getHost(props) + "/" + getTenantIdentifier() + "/" + v + "/transactions";
    return executePostRequest(url, body, buildHeaders(props));
  }

  private String executeGetRequest(String url, HttpHeaders headers) {
    try {
      HttpEntity<Void> entity = new HttpEntity<>(headers);
      log.info("Remittance GET: {}", url);
      ResponseEntity<String> response =
          restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, String.class);
      log.debug("Remittance GET response: {}", response.getBody());
      return response.getBody();
    } catch (Exception e) {
      log.error("Error executing remittance GET to {}: {}", url, e.getMessage(), e);
      throw new RuntimeException("Failed to execute remittance GET: " + e.getMessage(), e);
    }
  }

  private String executePostRequest(String url, Object request, HttpHeaders headers) {
    try {
      HttpEntity<Object> entity = new HttpEntity<>(request, headers);
      log.info("Remittance POST: {} payload={}", url, request);
      ResponseEntity<String> response =
          restTemplate.postForEntity(URI.create(url), entity, String.class);
      log.debug("Remittance POST response: {}", response.getBody());
      return response.getBody();
    } catch (Exception e) {
      log.error("Error executing remittance POST to {}: {}", url, e.getMessage(), e);
      throw new RuntimeException("Failed to execute remittance POST: " + e.getMessage(), e);
    }
  }

  private String executeDeleteRequest(String url, Object request, HttpHeaders headers) {
    try {
      HttpEntity<Object> entity = new HttpEntity<>(request, headers);
      log.info("Remittance DELETE: {}", url);
      ResponseEntity<String> response =
          restTemplate.exchange(URI.create(url), HttpMethod.DELETE, entity, String.class);
      return response.getBody();
    } catch (Exception e) {
      log.error("Error executing remittance DELETE to {}: {}", url, e.getMessage(), e);
      throw new RuntimeException("Failed to execute remittance DELETE: " + e.getMessage(), e);
    }
  }
}
