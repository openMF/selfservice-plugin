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
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.selfservice.external.client.ExternalIdentitySystemClient;
import org.apache.fineract.selfservice.registration.api.SelfServiceRetrieveIdentityRequest;
import org.apache.fineract.selfservice.registration.data.PersonIdentityData;
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

  private final ExternalIdentitySystemClient externalIdentitySystemClient;
  private final SelfServiceOnboardingStepService onboardingStepService;
  private final JdbcTemplate jdbcTemplate;

  @Override
  public PersonIdentityData retrieveClientIdentityData(
      SelfServiceRetrieveIdentityRequest apiRequestBodyAsJson) throws Exception {

    ObjectMapper objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    ResponseEntity<JsonNode> response =
        this.externalIdentitySystemClient.sendGetRequest(apiRequestBodyAsJson.externalId);

    PersonIdentityData data;
    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
      log.info("PERSON DATA response {}", response.getBody());
      JsonNode externalSystemPersonData = response.getBody();
      data = objectMapper.treeToValue(externalSystemPersonData, PersonIdentityData.class);
      if (data == null) {
        data = new PersonIdentityData();
      }
    } else {
      data = new PersonIdentityData();
    }

    enrichWithLocalData(data, apiRequestBodyAsJson.externalId);
    return data;
  }

  /**
   * Resolves local self-service user linked to client.external_id and enriches the identity
   * payload with:
   * <ul>
   *   <li>userId / username / pendingConfirmation
   *   <li>onboarding progress
   *   <li>email (m_client.email_address preferred, fallback m_appselfservice_user.email)
   *   <li>mobileNo (m_client.mobile_no)
   * </ul>
   * Failures are non-fatal so external identity data is still returned.
   * Multi-tenant safe: JdbcTemplate is bound to the current tenant DataSource.
   */
  private void enrichWithLocalData(PersonIdentityData data, String externalId) {
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

      Map<String, Object> row = jdbcTemplate.queryForMap(sql, externalId.trim());

      Long userId =
          row.get("user_id") != null ? ((Number) row.get("user_id")).longValue() : null;
      if (userId == null) {
        return;
      }

      data.setUserId(userId);
      data.setUsername(row.get("username") != null ? String.valueOf(row.get("username")) : null);

      Boolean enabled = row.get("is_enabled") != null ? (Boolean) row.get("is_enabled") : null;
      data.setPendingConfirmation(enabled != null && !enabled);

      // Email: prefer client, fall back to self-service user
      String clientEmail = toStringOrNull(row.get("client_email"));
      String userEmail = toStringOrNull(row.get("user_email"));
      data.setEmail(StringUtils.isNotBlank(clientEmail) ? clientEmail : userEmail);

      // Mobile / phone from core client record
      data.setMobileNo(toStringOrNull(row.get("client_mobile")));

      try {
        OnboardingProgressData onboarding = onboardingStepService.getOrInitProgress(userId);
        data.setOnboarding(onboarding);
      } catch (Exception e) {
        log.warn(
            "Identity retrieve: could not load onboarding for userId={} externalId={} (non-fatal)",
            userId,
            externalId,
            e);
      }
    } catch (EmptyResultDataAccessException e) {
      // No mapped self-service user – still try to enrich contact data from m_client only
      enrichContactFromClientOnly(data, externalId);
      log.info("Identity retrieve: no local self-service user for externalId={}", externalId);
    } catch (Exception e) {
      log.warn(
          "Identity retrieve: failed to resolve local user for externalId={} (non-fatal)",
          externalId,
          e);
    }
  }

  /**
   * Fallback when there is no self-service user mapping: still pull email/mobile from m_client.
   */
  private void enrichContactFromClientOnly(PersonIdentityData data, String externalId) {
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
      Map<String, Object> row = jdbcTemplate.queryForMap(sql, externalId.trim());
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

  private static String toStringOrNull(Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value).trim();
    return s.isEmpty() ? null : s;
  }
}