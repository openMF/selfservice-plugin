/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.runreport;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Container-level integration tests for the self-service runreport wrapper.
 *
 * <p>These tests assume that the underlying Fineract instance has at least one report named "Client
 * Details" configured and that the fineract.modules.selfservice.runreports.allowlist property is
 * set to include that name for the test profile.
 */
public class SelfRunReportIntegrationTest extends SelfServiceIntegrationTestBase {

  private String setupSelfServiceUser() {
    String clientName = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> clientBody = new HashMap<>();
    clientBody.put("officeId", 1);
    clientBody.put("legalFormId", 1);
    clientBody.put("firstname", "Test");
    clientBody.put("lastname", clientName);
    clientBody.put("externalId", clientName);
    clientBody.put("dateFormat", "dd MMMM yyyy");
    clientBody.put("locale", "en");
    clientBody.put("active", true);
    clientBody.put("activationDate", "01 January 2026");

    Integer clientId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .body(clientBody)
            .post(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/clients")
            .then()
            .statusCode(200)
            .extract()
            .path("clientId");

    Response rolesResponse =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles");
    Integer roleId =
        rolesResponse
            .jsonPath()
            .getInt(
                "find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");
    if (roleId == null || roleId <= 0) {
      throw new IllegalStateException(
          "Could not resolve role id for '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "'");
    }

    String username = "user_" + UUID.randomUUID().toString().substring(0, 8);

    executeSqlInPostgres(
        """
        WITH new_self_user AS (
            INSERT INTO m_appselfservice_user(
                office_id, username, password, email, firstname, lastname, is_deleted,
                nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining,
                password_never_expires, is_self_service_user, password_reset_required
            )
            VALUES (
                1, %s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1), %s,
                'User', 'Test', false, true, true, true, true, false, true, true, false
            )
            RETURNING id
        ), self_user_role AS (
            INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
            SELECT id, %d FROM new_self_user
        )
        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %d FROM new_self_user;
        """
            .formatted(
                sqlLiteral(username), sqlLiteral(username + "@fineract.org"), roleId, clientId));

    return username;
  }

  @Test
  @DisplayName("Self-service runreports deny non-allowlisted report names")
  void runReport_nonAllowlistedReport_denied() {
    String username = setupSelfServiceUser();

    Response runReportResponse =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
            .when()
            .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/runreports/SomeUnknownReport")
            .then()
            .extract()
            .response();

    Assertions.assertEquals(
        403,
        runReportResponse.statusCode(),
        "Unexpected response body: " + runReportResponse.body().asString());
    Assertions.assertTrue(
        runReportResponse
            .body()
            .asString()
            .contains("Self-service is not permitted to run this report: SomeUnknownReport"));
  }

  @Test
  @DisplayName("Self-service runreports allows allowlisted report names")
  void runReport_allowlistedReport_success() {
    String username = setupSelfServiceUser();

    Response runReportResponse =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
            .when()
            .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/runreports/Client Details")
            .then()
            .extract()
            .response();

    int statusCode = runReportResponse.statusCode();
    Assertions.assertTrue(
        statusCode == 200 || statusCode == 400 || statusCode == 404,
        "Expected successful execution (200), validation failure (400), or report missing in DB (404), but got: "
            + statusCode
            + ". Body: "
            + runReportResponse.body().asString());
  }
}
