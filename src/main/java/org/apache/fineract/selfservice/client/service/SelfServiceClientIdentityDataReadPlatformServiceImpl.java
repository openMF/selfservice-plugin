/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.client.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.selfservice.external.client.ExternalIdentitySystemClient;
import org.apache.fineract.selfservice.registration.api.SelfServiceRetrieveIdentityRequest;
import org.apache.fineract.selfservice.registration.data.PersonIdentityData;
import org.apache.fineract.selfservice.registration.exception.SelfServiceExternalIdentityException;
import org.apache.fineract.selfservice.registration.exception.SelfServiceExternalIdentityNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    name = "mifos.self.service.external.identity.system.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SelfServiceClientIdentityDataReadPlatformServiceImpl
    implements SelfServiceClientIdentityDataReadPlatformService {

  private static final String RESOURCE_NAME = "identity.retrieve";
  private static final String PARAM_EXTERNAL_ID = "externalId";

  private final ExternalIdentitySystemClient externalIdentitySystemClient;
  private final SelfServiceOnboardingStepService onboardingStepService;
  private final JdbcTemplate jdbcTemplate;

  @Override
  public PersonIdentityData retrieveClientIdentityData(
      final SelfServiceRetrieveIdentityRequest apiRequestBodyAsJson) throws Exception {

    validateRequest(apiRequestBodyAsJson);

    final String externalId = apiRequestBodyAsJson.getExternalId().trim();

    final ObjectMapper objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    final ResponseEntity<JsonNode> response;
    try {
      response = this.externalIdentitySystemClient.sendGetRequest(externalId);
    } catch (Exception ex) {
      log.error("External identity system call failed for externalId={}", externalId, ex);
      throw new SelfServiceExternalIdentityException(
          externalId, ex.getMessage() != null ? ex.getMessage() : "upstream call failed");
    }

    final JsonNode body = response.getBody();

    // Non-2xx HTTP from upstream
    if (!response.getStatusCode().is2xxSuccessful() || body == null) {
      log.warn(
          "External identity system returned status={} bodyNull={} for externalId={}",
          response.getStatusCode(),
          body == null,
          externalId);
      throw new SelfServiceExternalIdentityException(
          externalId, "HTTP " + response.getStatusCode().value());
    }

    log.info("PERSON DATA response {}", body);

    // Business-level error payload from the external system, e.g.:
    // { "status": "ERROR", "descripcion": "La cédula ..., no reporta información" }
    if (isExternalErrorPayload(body)) {
      final String description = extractErrorDescription(body);
      log.warn(
          "External identity system reported ERROR for externalId={}: {}",
          externalId,
          description);
      throw new SelfServiceExternalIdentityNotFoundException(externalId, description);
    }

    PersonIdentityData data = objectMapper.treeToValue(body, PersonIdentityData.class);
    if (data == null) {
      data = new PersonIdentityData();
    }
    // Ensure the requested externalId is always present on the response
    if (StringUtils.isBlank(data.getExternalId())) {
      data.setExternalId(externalId);
    }

    enrichWithLocalData(data, externalId);
    return data;
  }

  // -------------------------------------------------------------------------
  // Validation & external-error detection
  // -------------------------------------------------------------------------

  private void validateRequest(final SelfServiceRetrieveIdentityRequest request) {
    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator =
        new DataValidatorBuilder(errors).resource(RESOURCE_NAME);

    final String externalId = request != null ? request.getExternalId() : null;
    validator.reset().parameter(PARAM_EXTERNAL_ID).value(externalId).notBlank();

    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }

  /**
   * Detects the error envelope used by the national-ID provider, e.g.:
   *
   * <pre>{@code { "status": "ERROR", "descripcion": "..." }}</pre>
   *
   * Also treats an empty object / missing person fields after an explicit error status as an
   * error.
   */
  private boolean isExternalErrorPayload(final JsonNode body) {
    if (body == null || body.isNull()) {
      return true;
    }
    final JsonNode statusNode = body.get("status");
    if (statusNode != null && statusNode.isTextual()) {
      final String status = statusNode.asText("").trim();
      if ("ERROR".equalsIgnoreCase(status)
          || "FAIL".equalsIgnoreCase(status)
          || "FAILED".equalsIgnoreCase(status)) {
        return true;
      }
    }
    // Some providers use "codigo" / "code" with non-success values
    final JsonNode codeNode = body.has("codigo") ? body.get("codigo") : body.get("code");
    if (codeNode != null && codeNode.isTextual()) {
      final String code = codeNode.asText("").trim();
      if ("ERROR".equalsIgnoreCase(code) || "NOT_FOUND".equalsIgnoreCase(code)) {
        return true;
      }
    }
    return false;
  }

  private String extractErrorDescription(final JsonNode body) {
    if (body == null) {
      return null;
    }
    // Preferred field from the CrediD / national-ID provider
    for (String field : new String[] {"descripcion", "description", "message", "mensaje", "error"}) {
      final JsonNode node = body.get(field);
      if (node != null && node.isTextual() && StringUtils.isNotBlank(node.asText())) {
        return node.asText().trim();
      }
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Local enrichment (unchanged behaviour, multi-tenant via tenant JdbcTemplate)
  // -------------------------------------------------------------------------

  /**
   * Resolves local self-service user linked to client.external_id and enriches the identity
   * payload with userId / username / pendingConfirmation / onboarding / email / mobileNo.
   * Failures are non-fatal so external identity data is still returned.
   */
  private void enrichWithLocalData(final PersonIdentityData data, final String externalId) {
    if (data == null || StringUtils.isBlank(externalId)) {
      return;
    }

    try {
      final String sql =
          """
          SELECT
            u.id            AS user_id,
            u.username      AS username,
            u.enabled       AS is_enabled,
            u.email         AS user_email,
            c.email_address AS client_email,
            c.mobile_no     AS client_mobile
          FROM m_client c
          INNER JOIN m_selfservice_user_client_mapping m ON m.client_id = c.id
          INNER JOIN m_appselfservice_user u ON u.id = m.appuser_id
          WHERE c.external_id = ?
          LIMIT 1
          """;

      final Map<String, Object> row = jdbcTemplate.queryForMap(sql, externalId.trim());

      final Long userId =
          row.get("user_id") != null ? ((Number) row.get("user_id")).longValue() : null;
      if (userId == null) {
        return;
      }

      data.setUserId(userId);
      data.setUsername(row.get("username") != null ? String.valueOf(row.get("username")) : null);

      final Object enabledObj = row.get("is_enabled");
      final boolean enabled =
          enabledObj instanceof Boolean
              ? (Boolean) enabledObj
              : enabledObj != null && Boolean.parseBoolean(String.valueOf(enabledObj));
      data.setPendingConfirmation(!enabled);

      final String clientEmail = toStringOrNull(row.get("client_email"));
      final String userEmail = toStringOrNull(row.get("user_email"));
      data.setEmail(StringUtils.isNotBlank(clientEmail) ? clientEmail : userEmail);
      data.setMobileNo(toStringOrNull(row.get("client_mobile")));

      try {
        final OnboardingProgressData onboarding = onboardingStepService.getOrInitProgress(userId);
        data.setOnboarding(onboarding);
      } catch (Exception e) {
        log.warn(
            "Identity retrieve: could not load onboarding for userId={} externalId={} (non-fatal)",
            userId,
            externalId,
            e);
      }
    } catch (EmptyResultDataAccessException e) {
      enrichContactFromClientOnly(data, externalId);
      log.info("Identity retrieve: no local self-service user for externalId={}", externalId);
    } catch (Exception e) {
      log.warn(
          "Identity retrieve: failed to resolve local user for externalId={} (non-fatal)",
          externalId,
          e);
    }
  }

  private void enrichContactFromClientOnly(final PersonIdentityData data, final String externalId) {
    try {
      final String sql =
          """
          SELECT
            c.email_address AS client_email,
            c.mobile_no     AS client_mobile
          FROM m_client c
          WHERE c.external_id = ?
          LIMIT 1
          """;
      final Map<String, Object> row = jdbcTemplate.queryForMap(sql, externalId.trim());
      if (StringUtils.isBlank(data.getEmail())) {
        data.setEmail(toStringOrNull(row.get("client_email")));
      }
      if (StringUtils.isBlank(data.getMobileNo())) {
        data.setMobileNo(toStringOrNull(row.get("client_mobile")));
      }
    } catch (EmptyResultDataAccessException ignored) {
      // client itself not found – leave fields null
    } catch (Exception e) {
      log.warn(
          "Identity retrieve: failed to load client contact for externalId={} (non-fatal)",
          externalId,
          e);
    }
  }

  private static String toStringOrNull(final Object value) {
    if (value == null) {
      return null;
    }
    final String s = String.valueOf(value).trim();
    return s.isEmpty() ? null : s;
  }
}