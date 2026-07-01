package org.apache.fineract.selfservice.external.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;
import org.apache.fineract.infrastructure.configuration.service.ExternalApiRestServicesPropertiesReadPlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for the external notification gateway.
 *
 * <p>This bean is only created when the external system is explicitly enabled via {@code
 * mifos.self.service.external.sms.system.enabled=true}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "mifos.self.service.external.sms.system.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ExternalNotificationSystemClient {

  private final ExternalApiRestServicesPropertiesReadPlatformService
      externalApiRestServicesPropertiesReadPlatformService;

  private static final RestTemplate restTemplate = new RestTemplate();
  private static final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  public ExternalNotificationSystemClient(
      final ExternalApiRestServicesPropertiesReadPlatformService
          externalApiRestServicesPropertiesReadPlatformService) {
    this.externalApiRestServicesPropertiesReadPlatformService =
        externalApiRestServicesPropertiesReadPlatformService;
  }

  public void sendPostRequest(Object requestBody) {
    NotificationCredentialsData credentials = resolveNotificationCredentials();

    if (credentials == null || !credentials.isEnabled()) {
      log.debug(
          "External notification system is disabled or credentials are missing. Skipping external send.");
      return;
    }

    CompletableFuture.runAsync(
        () -> {
          try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            if (credentials.getHeader() != null && credentials.getHeaderValue() != null) {
              headers.set(credentials.getHeader(), credentials.getHeaderValue());
            }

            // Serialize the Map/DTO directly to JSON string (no pretty-printing to save bandwidth)
            String json = objectMapper.writeValueAsString(requestBody);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);

            String host = credentials.getHost();
            if (host == null || host.isBlank()) {
              log.error("External notification host URL is not configured in the database.");
              return;
            }

            restTemplate.exchange(host, HttpMethod.POST, entity, JsonNode.class);
            log.debug("Successfully sent external notification to {}", host);
          } catch (Exception e) {
            log.error("Async external notification request failed", e);
          }
        });
  }

  /**
   * Retrieves the notification credentials from the c_external_service and
   * c_external_service_properties tables via the Fineract core service.
   */
  public NotificationCredentialsData resolveNotificationCredentials() {
    try {
      NotificationCredentialsData credentials =
          this.externalApiRestServicesPropertiesReadPlatformService.getNotificationCredentials();
      return credentials != null ? credentials : new NotificationCredentialsData();
    } catch (DataAccessException dae) {
      log.warn(
          "Notification Service configuration unavailable in database, falling back to legacy notifications. Error: {}",
          dae.getMessage());
      return new NotificationCredentialsData();
    } catch (Exception e) {
      log.error("Unexpected error retrieving Notification Service configuration from database", e);
      return new NotificationCredentialsData();
    }
  }
}
