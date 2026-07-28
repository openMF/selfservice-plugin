/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = SelfServiceSecurityTestConfig.class,
    properties = "spring.main.allow-bean-definition-overriding=true")
@ActiveProfiles("test")
@TestPropertySource("classpath:application-test.properties")
@AutoConfigureMockMvc
class SelfServiceSecurityFilterChainIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SelfServiceAuthenticationTokenService selfServiceAuthenticationTokenService;

  @Test
  void registrationEndpoint_isPublicAndReturns200() throws Exception {
    // POST /v1/self/registration without any Auth header should not return 401
    ResultMatcher notUnauthorized =
        result -> {
          int status = result.getResponse().getStatus();
          if (status == 401 || status == 404) {
            throw new AssertionError("Expected status not to be 401 or 404, but was " + status);
          }
        };

    mockMvc
        .perform(
            post("/v1/self/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"ACC001\",\"tenantIdentifier\":\"default\"}"))
        .andExpect(notUnauthorized); // May be 404, 400, etc., but not blocked by filter chain
  }

  @Test
  void forgotPasswordEndpoints_arePublicAndNotBlockedByAuthentication() throws Exception {
    ResultMatcher notUnauthorized =
        result -> {
          int status = result.getResponse().getStatus();
          if (status == 401 || status == 403 || status == 404 || (status >= 500 && status < 600)) {
            throw new AssertionError(
                "Expected forgot-password endpoint to be reachable and not blocked by auth, but was"
                    + " "
                    + status);
          }
        };

    mockMvc
        .perform(
            post("/v1/self/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"authenticationMode\":\"email\"}"))
        .andExpect(notUnauthorized);

    mockMvc
        .perform(
            post("/v1/self/password/renew")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"externalAuthenticationToken\":\"123456\",\"password\":\"Strong#Abc123\",\"repeatPassword\":\"Strong#Abc123\"}"))
        .andExpect(notUnauthorized);
  }

  @Test
  void protectedEndpoint_withoutAuthReturns401() throws Exception {
    ResultMatcher unauthorizedOrForbidden =
        result -> {
          int status = result.getResponse().getStatus();
          if (status != 401 && status != 403) {
            throw new AssertionError("Expected status to be 401 or 403, but was " + status);
          }
        };

    mockMvc
        .perform(get("/v1/self/clients").header("Fineract-Platform-TenantId", "default"))
        .andExpect(unauthorizedOrForbidden);
  }

  @Test
  void nonSelfEndpoint_isNotAffectedByOurFilterChain() throws Exception {
    ResultMatcher notUnauthorized =
        result -> {
          if (result.getResponse().getStatus() == 401) {
            throw new AssertionError("Expected status not to be 401");
          }
        };

    mockMvc.perform(get("/actuator/health")).andExpect(notUnauthorized);
  }

  @Test
  void loanSimulationProducts_isPublicAndNotBlockedByAuthentication() throws Exception {
    ResultMatcher notAuthBlocked =
        result -> {
          int status = result.getResponse().getStatus();
          if (status == 401 || status == 403) {
            throw new AssertionError(
                "Expected public loan simulation products endpoint to not be blocked by auth,"
                    + " but was "
                    + status);
          }
        };

    mockMvc
        .perform(get("/v1/self/loans/simulate/products").accept(MediaType.APPLICATION_JSON))
        .andExpect(notAuthBlocked);
  }

  @Test
  void loanSimulationTemplate_isPublicAndNotBlockedByAuthentication() throws Exception {
    ResultMatcher notAuthBlocked =
        result -> {
          int status = result.getResponse().getStatus();
          if (status == 401 || status == 403) {
            throw new AssertionError(
                "Expected public loan simulation template endpoint to not be blocked by auth,"
                    + " but was "
                    + status);
          }
        };

    mockMvc
        .perform(
            get("/v1/self/loans/simulate/template")
                .queryParam("templateType", "individual")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(notAuthBlocked);
  }

  @Test
  void loanSimulationCalculate_isPublicAndNotBlockedByAuthentication() throws Exception {
    ResultMatcher notAuthBlocked =
        result -> {
          int status = result.getResponse().getStatus();
          if (status == 401 || status == 403) {
            throw new AssertionError(
                "Expected public loan simulation calculate endpoint to not be blocked by auth,"
                    + " but was "
                    + status);
          }
        };

    mockMvc
        .perform(
            post("/v1/self/loans/simulate")
                .queryParam("command", "calculateLoanSchedule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"productId\":1,\"principal\":10000,\"loanTermFrequency\":12,"
                        + "\"loanTermFrequencyType\":2,\"numberOfRepayments\":12,"
                        + "\"repaymentEvery\":1,\"repaymentFrequencyType\":2,"
                        + "\"interestRatePerPeriod\":12,\"amortizationType\":1,"
                        + "\"interestType\":0,\"interestCalculationPeriodType\":1,"
                        + "\"expectedDisbursementDate\":\"01 June 2026\","
                        + "\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\","
                        + "\"dateFormat\":\"dd MMMM yyyy\",\"locale\":\"en\"}"))
        .andExpect(notAuthBlocked);
  }
}
