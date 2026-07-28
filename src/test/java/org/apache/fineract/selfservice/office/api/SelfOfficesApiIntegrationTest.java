/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelfOfficesApiIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String SELF_OFFICES_PATH = SelfServiceTestUtils.SELF_OFFICES_PATH;

  private static String selfServiceUsername;
  private static int officeId;

  @BeforeAll
  static void seedTestData() {
    String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    selfServiceUsername = "office_user_" + uniqueSuffix;
    officeId = 1;

    executeSqlInPostgres(
        """
INSERT INTO m_selfservice_office_service(office_id, service_name, service_external_id, working_hours)
VALUES (%s, %s, %s, %s);
""",
        officeId, "Account Opening " + uniqueSuffix, "SVC-" + uniqueSuffix, "Mon-Fri 09:00-17:00");

    executeSqlInPostgres(
        """
        INSERT INTO m_selfservice_office_geolocation(office_id, latitude, longitude)
        SELECT %s, 19.4326077, -99.1332080
        WHERE NOT EXISTS (
            SELECT 1 FROM m_selfservice_office_geolocation WHERE office_id = %s
        );
        """,
        officeId, officeId);

    Map<String, Object> clientBody = new HashMap<>();
    clientBody.put("officeId", 1);
    clientBody.put("legalFormId", 1);
    clientBody.put("firstname", "OffTest");
    clientBody.put("lastname", "cli_" + uniqueSuffix);
    clientBody.put("externalId", "offcli_" + uniqueSuffix);
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

    assertThat(clientId).as("Created clientId must be present").isNotNull();

    Integer roleId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles")
            .jsonPath()
            .getInt("find { it.name == 'Self Service User' }.id");

    assertThat(roleId).as("Self Service User role must exist").isNotNull();

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
                'OffTest', 'User', false, true, true, true, true, false, true, true, false
            )
            RETURNING id
        ), self_user_role AS (
            INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
            SELECT id, %s FROM new_self_user
        )
        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %s FROM new_self_user;
        """,
        selfServiceUsername, selfServiceUsername + "@fineract.org", roleId, clientId);
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/details without auth returns 403")
  void retrieveDetails_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SELF_OFFICES_PATH + "/" + officeId + "/details")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/details with superuser returns 401")
  void retrieveDetails_withSuperUser_returns401() {
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .when()
        .get(SELF_OFFICES_PATH + "/" + officeId + "/details")
        .then()
        .statusCode(401);
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/details with self-service user returns 200")
  void retrieveDetails_withSelfServiceUser_returns200() {
    Response response =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), selfServiceUsername, "password"))
            .when()
            .get(SELF_OFFICES_PATH + "/" + officeId + "/details")
            .then()
            .statusCode(200)
            .extract()
            .response();

    assertThat(response.jsonPath().getLong("id")).isEqualTo(officeId);
    assertThat(response.jsonPath().getString("name")).isNotBlank();
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/services without auth returns 403")
  void retrieveServices_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SELF_OFFICES_PATH + "/" + officeId + "/services")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/services with self-service user returns 200")
  void retrieveServices_withSelfServiceUser_returns200() {
    Response response =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), selfServiceUsername, "password"))
            .when()
            .get(SELF_OFFICES_PATH + "/" + officeId + "/services")
            .then()
            .statusCode(200)
            .extract()
            .response();

    List<?> services = response.jsonPath().getList("$");
    assertThat(services).isNotEmpty();
    assertThat(response.jsonPath().getString("[0].serviceName")).isNotBlank();
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/geolocation without auth returns 403")
  void retrieveGeolocation_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SELF_OFFICES_PATH + "/" + officeId + "/geolocation")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/geolocation with self-service user returns 200")
  void retrieveGeolocation_withSelfServiceUser_returns200() {
    Response response =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), selfServiceUsername, "password"))
            .when()
            .get(SELF_OFFICES_PATH + "/" + officeId + "/geolocation")
            .then()
            .statusCode(200)
            .extract()
            .response();

    assertThat(response.jsonPath().getFloat("latitude")).isNotZero();
    assertThat(response.jsonPath().getFloat("longitude")).isNotZero();
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/address without auth returns 403")
  void retrieveAddress_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SELF_OFFICES_PATH + "/" + officeId + "/address")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("GET /v1/self/offices/{id}/address with self-service user returns success")
  void retrieveAddress_withSelfServiceUser_returnsSuccess() {
    int statusCode =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), selfServiceUsername, "password"))
            .when()
            .get(SELF_OFFICES_PATH + "/" + officeId + "/address")
            .then()
            .extract()
            .statusCode();

    assertThat(statusCode).isIn(200, 204);
  }

  @Test
  @DisplayName("GET /v1/self/offices/999999/details for non-existent office returns 404")
  void retrieveDetails_nonExistentOffice_returns404() {
    given(
            SelfServiceTestUtils.requestSpecWithAuth(
                getFineractPort(), selfServiceUsername, "password"))
        .when()
        .get(SELF_OFFICES_PATH + "/999999/details")
        .then()
        .statusCode(404);
  }

  @Test
  @DisplayName("GET /v1/self/offices/999999/services for non-existent office returns 404")
  void retrieveServices_nonExistentOffice_returns404() {
    given(
            SelfServiceTestUtils.requestSpecWithAuth(
                getFineractPort(), selfServiceUsername, "password"))
        .when()
        .get(SELF_OFFICES_PATH + "/999999/services")
        .then()
        .statusCode(404);
  }
}
