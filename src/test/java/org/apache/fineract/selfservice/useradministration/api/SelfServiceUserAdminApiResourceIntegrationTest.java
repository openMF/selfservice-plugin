/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.api;

import static io.restassured.RestAssured.given;

import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.Test;

class SelfServiceUserAdminApiResourceIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String ADMIN_USERS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/selfservice/users";
  private static final String PUBLIC_SELF_USERS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/users";

  @Test
  void retrieveAll_shouldRouteAuthenticatedBackofficeRequest() {
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(ADMIN_USERS_PATH)
        .then()
        .statusCode(200);
  }

  @Test
  void retrieveOne_shouldRouteAuthenticatedBackofficeRequest() {
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(ADMIN_USERS_PATH + "/999999999")
        .then()
        .statusCode(404);
  }

  @Test
  void retrieveAll_shouldNotBeExposedUnderPublicSelfApi() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(PUBLIC_SELF_USERS_PATH)
        .then()
        .statusCode(403);
  }
}
