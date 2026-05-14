/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.savings.api;

import static io.restassured.RestAssured.given;

import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelfSavingsApiIntegrationTest extends SelfServiceIntegrationTestBase {

  @Test
  @DisplayName("GET /v1/self/savingsaccounts without auth returns 403")
  void retrieveAll_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SelfServiceTestUtils.SELF_SAVINGS_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("GET /v1/self/savingsaccounts with mifos returns 401 (Not a Self Service User)")
  void retrieveAll_withSuperUser_returns401() {
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(SelfServiceTestUtils.SELF_SAVINGS_PATH)
        .then()
        .statusCode(401);
  }
}
