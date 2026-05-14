/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.api;

import static io.restassured.RestAssured.given;

import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelfServiceRegistrationIntegrationTest extends SelfServiceIntegrationTestBase {

  @Test
  @DisplayName("POST /v1/self/registration with missing body returns 500")
  void createRegistration_emptyBody_returns500() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .body("{}")
        .when()
        .post(SelfServiceTestUtils.SELF_REGISTRATION_PATH)
        .then()
        .statusCode(500);
  }

  @Test
  @DisplayName("POST /v1/self/registration with invalid client logic returns 404")
  void createRegistration_invalidClient_returns404() {
    String payload =
        """
        {
          "accountNumber": "000000000",
          "firstName": "Inv",
          "lastName": "alid",
          "username": "invaliduser",
          "password": "Strong#Abc123",
          "authenticationMode": "email",
          "email": "invalid@test.com"
        }
        """;

    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .body(payload)
        .when()
        .post(SelfServiceTestUtils.SELF_REGISTRATION_PATH)
        .then()
        .statusCode(404);
  }
}
