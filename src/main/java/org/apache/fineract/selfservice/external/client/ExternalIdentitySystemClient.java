package org.apache.fineract.selfservice.external.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.data.NationalIdCredentialsData;
import org.apache.fineract.infrastructure.configuration.service.ExternalApiRestServicesPropertiesReadPlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for the external identity verification system.
 *
 * <p>This bean is only created when the external identity system is explicitly enabled via {@code
 * mifos.self.service.external.identity.system.enabled=true}. Without this guard, the mandatory
 * {@code @Value} properties ({@code url}, {@code header}, {@code token}) would crash the entire
 * application context on startup when the properties aren't configured — which is the normal case
 * for most deployments.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "mifos.self.service.external.identity.system.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ExternalIdentitySystemClient {

  private final ExternalApiRestServicesPropertiesReadPlatformService
      externalApiRestServicesPropertiesReadPlatformService;

  @Autowired
  public ExternalIdentitySystemClient(
      final ExternalApiRestServicesPropertiesReadPlatformService
          externalApiRestServicesPropertiesReadPlatformService) {
    this.externalApiRestServicesPropertiesReadPlatformService =
        externalApiRestServicesPropertiesReadPlatformService;
  }

  // Kept static as per original code, assuming a simple RestTemplate configuration is sufficient
  private static final RestTemplate restTemplate = new RestTemplate();

  public ResponseEntity<JsonNode> sendGetRequest(String externalId) throws Exception {
    NationalIdCredentialsData nationalIdCredentialsData = resolveNationalIdCredentials();

    String url = nationalIdCredentialsData.getHost() + externalId;

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(nationalIdCredentialsData.getHeader(), nationalIdCredentialsData.getHeaderValue());

    HttpEntity<String> entity = new HttpEntity<>(headers);

    return restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, JsonNode.class);
  }

  NationalIdCredentialsData resolveNationalIdCredentials() {
    NationalIdCredentialsData nationalIdCredentialsData = new NationalIdCredentialsData();
    try {
      nationalIdCredentialsData =
          this.externalApiRestServicesPropertiesReadPlatformService.getNationalIdCredentials();
    } catch (DataAccessException dae) {
      log.warn("National Id Service configuration unavailable, falling back to Spring properties ");
    }
    return nationalIdCredentialsData;
  }
}
