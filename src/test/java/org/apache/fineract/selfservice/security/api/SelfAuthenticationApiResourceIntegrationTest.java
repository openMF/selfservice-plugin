/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import static io.restassured.RestAssured.given;

import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration Test ensuring the Spring Context starts, Testcontainers database connects, and
 * RestAssured correctly maps HTTP traffic to the embedded Fineract endpoints.
 *
 * <p>Named *IntegrationTest.java so it is executed during the Failsafe 'verify' phase.
 */
class SelfAuthenticationApiResourceIntegrationTest extends SelfServiceIntegrationTestBase {

  @Test
  @DisplayName("POST /v1/self/authentication with missing credentials returns 500")
  void authenticate_missingCredentials_returns500() {
    // Proves the web server is up and the endpoint rejects empty credentials at the filter layer
    String emptyBody = "{}";

    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .body(emptyBody)
        .when()
        .post(SelfServiceTestUtils.SELF_AUTH_PATH)
        .then()
        .statusCode(500);
  }

  @Test
  @DisplayName("POST /v1/self/authentication with invalid credentials returns 401 Unauthorized")
  void authenticate_invalidCredentials_returns401() {
    // Proves that the Spring Security filter chain and the custom Self Service
    // Authentication provider are actively rejecting bad credentials
    String invalidBody = "{\"username\":\"fakeUser\",\"password\":\"fakePass\"}";

    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .body(invalidBody)
        .when()
        .post(SelfServiceTestUtils.SELF_AUTH_PATH)
        .then()
        .statusCode(401);
  }
}
