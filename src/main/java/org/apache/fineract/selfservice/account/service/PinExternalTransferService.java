package org.apache.fineract.selfservice.account.service;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.selfservice.account.data.PinTransferRequest;
import org.apache.fineract.selfservice.account.data.TptTransferRequest;
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
public class PinExternalTransferService {

  private final JdbcTemplate jdbcTemplate;
  private final RestTemplate restTemplate = new RestTemplate();

  private static final String SERVICE_NAME = "PinService";

  /**
   * Extrae dinámicamente las propiedades de la tabla c_external_service_properties según el nombre
   * del servicio PinService.
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

  /** Comprueba si el servicio está habilitado en la configuración de la base de datos. */
  private boolean isEnabled(Map<String, String> props) {
    return "true".equalsIgnoreCase(props.get("isEnabled"));
  }

  /** Recupera la URL del host base desde la configuración de la base de datos. */
  private String getHost(Map<String, String> props) {
    return props.getOrDefault("host", "");
  }

  /** Construye los encabezados HTTP, inyectando la API Key dinámica si está configurada. */
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

  public String executePinTransfer(PinTransferRequest request) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn("PinService is disabled in database. Skipping transfer.");
      return "{\"status\": \"disabled\", \"message\": \"PIN service is disabled\"}";
    }

    String url = getHost(props) + "/api/v1/transfers/transfer";
    return executePostRequest(url, request, buildHeaders(props));
  }

  public String executeTptTransfer(TptTransferRequest request) {
    log.info("Processing internal TPT transfer for account: {}", request.getToAccountId());
    return "{\"status\": \"success\", \"message\": \"TPT transfer processed successfully\"}";
  }

  public String getAccountInfo(String iban) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn("PinService is disabled in database. Skipping getAccountInfo.");
      return "{\"status\": \"disabled\", \"message\": \"PIN service is disabled\"}";
    }

    String cleanIban = iban.replaceAll("\\s+", "");
    String url = getHost(props) + "/api/v1/accounts/" + cleanIban + "/info";

    return executeGetRequest(url, buildHeaders(props));
  }

  // =====================================================================
  //  NUEVO MÉTODO: OBTENER DETALLE DE TRANSACCIÓN PIN
  // =====================================================================
  /**
   * Consulta el detalle de una transferencia PIN utilizando el número de recibo / referencia.
   *
   * @param referenceNumber El número de referencia o recibo de la transacción
   * @return String JSON con la respuesta recibida del API externo de PIN
   */
  public String getTransactionDetail(String referenceNumber) {
    Map<String, String> props = getServiceProperties();

    if (!isEnabled(props)) {
      log.warn("PinService is disabled in database. Skipping getTransactionDetail for ref: {}", referenceNumber);
      return "{\"status\": \"disabled\", \"message\": \"PIN service is disabled\"}";
    }

    String url = getHost(props) + "/api/v1/transfers/bySinpeReffNumber/" + referenceNumber;
    return executeGetRequest(url, buildHeaders(props));
  }

  // === CENTRALIZACIÓN DE INTERCAMBIO HTTP ===

  private String executePostRequest(String url, Object request, HttpHeaders headers) {
    try {
      HttpEntity<Object> entity = new HttpEntity<>(request, headers);
      log.info("Sending request to Dynamic Backend: {} with payload: {}", url, request);

      ResponseEntity<String> response =
              restTemplate.postForEntity(URI.create(url), entity, String.class);
      log.info("Received response from Dynamic Backend: {}", response.getBody());

      return response.getBody();
    } catch (Exception e) {
      log.error("Error executing external transfer to {}: {}", url, e.getMessage(), e);
      throw new RuntimeException("Failed to execute external transfer: " + e.getMessage(), e);
    }
  }

  private String executeGetRequest(String url, HttpHeaders headers) {
    try {
      HttpEntity<Void> entity = new HttpEntity<>(headers);
      log.info("Sending GET request to Dynamic Backend: {}", url);

      ResponseEntity<String> response =
              restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, String.class);
      log.info("Received response from Dynamic Backend: {}", response.getBody());

      return response.getBody();
    } catch (Exception e) {
      log.error("Error executing external GET request to {}: {}", url, e.getMessage(), e);
      throw new RuntimeException(
              "Failed to execute external account info fetch: " + e.getMessage(), e);
    }
  }
}