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

  /** Checks if the SinpeService is enabled in the database configuration. */
  private boolean isEnabled(Map<String, String> props) {
    return "true".equalsIgnoreCase(props.get("isEnabled"));
  }

  /** Retrieves the base host URL from the database configuration. */
  private String getHost(Map<String, String> props) {
    return props.getOrDefault("host", "");
  }

  /** Builds HTTP headers, injecting the custom API key header if configured in the database. */
  private HttpHeaders buildHeaders(Map<String, String> props) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    String headerName = props.get("header");
    String headerValue = props.get("headerValue");

    if (headerName != null && !headerName.isBlank() && headerValue != null) {
      headers.set(headerName, headerValue);
    }
    return headers;
  }

  public void createSubscription(SinpeSubscriptionRequest request) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping createSubscription for phone: {}",
          request.getPhoneNumber());
      return;
    }

    String url = getHost(props) + "/sinpe/subscription";
    HttpEntity<SinpeSubscriptionRequest> entity = new HttpEntity<>(request, buildHeaders(props));

    try {
      restTemplate.postForObject(url, entity, String.class);
      log.info("SINPE subscription created successfully for phone: {}", request.getPhoneNumber());
    } catch (Exception e) {
      log.error("Failed to create SINPE subscription for phone: {}", request.getPhoneNumber(), e);
      throw new RuntimeException("Failed to create SINPE subscription: " + e.getMessage(), e);
    }
  }

  public void editSubscription(SinpeSubscriptionEditRequest request) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping editSubscription for phone: {}",
          request.getPhoneNumber());
      return;
    }

    String url = getHost(props) + "/sinpe/subscription/edit";
    HttpEntity<SinpeSubscriptionEditRequest> entity =
        new HttpEntity<>(request, buildHeaders(props));

    try {
      restTemplate.postForObject(url, entity, String.class);
      log.info("SINPE subscription edited successfully for phone: {}", request.getPhoneNumber());
    } catch (Exception e) {
      log.error("Failed to edit SINPE subscription for phone: {}", request.getPhoneNumber(), e);
      throw new RuntimeException("Failed to edit SINPE subscription: " + e.getMessage(), e);
    }
  }

  public void deleteSubscription(String phoneNumber) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping deleteSubscription for phone: {}",
          phoneNumber);
      return;
    }

    String url = getHost(props) + "/sinpe/subscription/" + phoneNumber;
    HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(props));

    try {
      restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
      log.info("SINPE subscription deleted successfully for phone: {}", phoneNumber);
    } catch (Exception e) {
      log.error("Failed to delete SINPE subscription for phone: {}", phoneNumber, e);
      throw new RuntimeException("Failed to delete SINPE subscription: " + e.getMessage(), e);
    }
  }

  public void transferToPhone(SinpeTransferRequest request) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn(
          "SinpeService is disabled in c_external_service. Skipping transferToPhone for phone: {}",
          request.getDestinationPhone());
      return;
    }

    String url = getHost(props) + "/transfer/account-to-phone";
    HttpEntity<SinpeTransferRequest> entity = new HttpEntity<>(request, buildHeaders(props));
    
    log.info("request"+request);

    try {
      restTemplate.postForObject(url, entity, String.class);
      log.info("SINPE transfer to phone {} processed successfully", request.getDestinationPhone());
    } catch (Exception e) {
      log.error("Failed to process SINPE transfer to phone: {}", request.getDestinationPhone(), e);
      throw new RuntimeException("Failed to process SINPE transfer: " + e.getMessage(), e);
    }
  }
}
