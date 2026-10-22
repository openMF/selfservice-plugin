/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /v1/self/user} against a real Fineract + PostgreSQL started by Testcontainers.
 *
 * <p>Self-service users live in {@code m_appselfservice_user}; back-office users live in {@code
 * m_appuser}. The two tables have independent ids, so the password change must act on the
 * self-service row and never on a back-office row that happens to have the same id.
 */
class SelfUserPasswordChangeIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final String CP = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String OLD_PASSWORD = "password";
  private static final String NEW_PASSWORD = "Blue-Horizon7!";

  private static Integer roleId;
  private static Integer officeId;

  @BeforeAll
  static void seed() {
    roleId =
        given(admin())
            .get(CP + "/roles")
            .then()
            .statusCode(200)
            .extract()
            .path(
                "find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");
    officeId = 1;
  }

  @Test
  @DisplayName("a self-service user that has no back-office twin can change their password")
  void selfServiceOnlyUser_canChangePassword_newWorks_oldStops() {
    String username = insertSelfServiceUser(false);
    String auth = loginHeader(username, OLD_PASSWORD);

    Response response = put(auth, passwordBody(NEW_PASSWORD, NEW_PASSWORD));

    assertEquals(200, response.statusCode(), response.asString());
    assertEquals(true, response.path("changes.password"));
    assertNotNull(response.path("resourceId"));
    String body = response.asString();
    assertFalse(body.contains(NEW_PASSWORD), "the password must never be echoed");
    assertFalse(body.contains(selfServicePasswordHash(username)), "nor its hash");

    assertEquals(
        401,
        SelfServiceTestUtils.authenticate(getFineractPort(), username, OLD_PASSWORD).statusCode());
    assertEquals(
        200,
        SelfServiceTestUtils.authenticate(getFineractPort(), username, NEW_PASSWORD).statusCode());
  }

  @Test
  @DisplayName("a back-office user with the same id is not touched")
  void backOfficeUserWithTheSameId_isNotChanged() {
    String username = insertSelfServiceUser(true);
    long sharedId = selfServiceId(username);
    String victimPasswordBefore =
        querySingleValueInPostgres("SELECT password FROM m_appuser WHERE id = " + sharedId);
    String victimUsername =
        querySingleValueInPostgres("SELECT username FROM m_appuser WHERE id = " + sharedId);
    assertNotEquals(username, victimUsername, "the fixture needs two different people");
    String auth = loginHeader(username, OLD_PASSWORD);

    Response response = put(auth, passwordBody(NEW_PASSWORD, NEW_PASSWORD));

    assertEquals(200, response.statusCode(), response.asString());
    assertEquals(
        victimPasswordBefore,
        querySingleValueInPostgres("SELECT password FROM m_appuser WHERE id = " + sharedId),
        "the back-office user's password must not change");
    assertEquals(
        200,
        SelfServiceTestUtils.authenticate(getFineractPort(), username, NEW_PASSWORD).statusCode());
    assertEquals(
        401,
        SelfServiceTestUtils.authenticate(getFineractPort(), username, OLD_PASSWORD).statusCode());
  }

  @Test
  @DisplayName("the change is rejected before anything is saved when the request is wrong")
  void invalidRequests_changeNothing() {
    String username = insertSelfServiceUser(false);
    String auth = loginHeader(username, OLD_PASSWORD);
    String before = selfServicePasswordHash(username);

    assertEquals(400, put(auth, passwordBody(NEW_PASSWORD, "different")).statusCode());
    assertEquals(400, put(auth, Map.of("password", NEW_PASSWORD)).statusCode());
    assertEquals(400, put(auth, passwordBody(" ", " ")).statusCode());
    Map<String, Object> extra = passwordBody(NEW_PASSWORD, NEW_PASSWORD);
    extra.put("username", "someone-else");
    assertEquals(400, put(auth, extra).statusCode());

    assertEquals(before, selfServicePasswordHash(username));
    assertEquals(
        200,
        SelfServiceTestUtils.authenticate(getFineractPort(), username, OLD_PASSWORD).statusCode());
  }

  @Test
  @DisplayName("without a login the endpoint is closed")
  void unauthenticated_isRejected() {
    Response response =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(passwordBody(NEW_PASSWORD, NEW_PASSWORD))
            .put(CP + "/self/user");
    assertTrue(response.statusCode() == 401 || response.statusCode() == 403, response.asString());
  }

  // ---------------------------------------------------------------------------------------------

  /** One client per user: a client can be linked to a single self-service user. */
  private static Integer createClient() {
    String today =
        LocalDate.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH));
    Map<String, Object> client = new HashMap<>();
    client.put("officeId", 1);
    client.put("legalFormId", 1);
    client.put("firstname", "Pwd");
    client.put("lastname", UUID.randomUUID().toString().substring(0, 8));
    client.put("dateFormat", DATE_FORMAT);
    client.put("locale", "en_GB");
    client.put("active", true);
    client.put("activationDate", today);
    return given(admin())
        .body(client)
        .post(CP + "/clients")
        .then()
        .statusCode(200)
        .extract()
        .path("clientId");
  }

  private static Response put(String authHeader, Map<String, Object> body) {
    return given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .header("Authorization", authHeader)
        .body(body)
        .put(CP + "/self/user");
  }

  private static Map<String, Object> passwordBody(String password, String repeat) {
    Map<String, Object> body = new HashMap<>();
    body.put("password", password);
    body.put("repeatPassword", repeat);
    return body;
  }

  private static RequestSpecification admin() {
    return SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password");
  }

  private static String loginHeader(String username, String password) {
    Response response = SelfServiceTestUtils.authenticate(getFineractPort(), username, password);
    assertEquals(200, response.statusCode(), response.asString());
    return "Basic " + response.<String>path("base64EncodedAuthenticationKey");
  }

  private static long selfServiceId(String username) {
    return Long.parseLong(
        querySingleValueInPostgres(
            "SELECT id FROM m_appselfservice_user WHERE username = " + sqlLiteral(username)));
  }

  private static String selfServicePasswordHash(String username) {
    return querySingleValueInPostgres(
        "SELECT password FROM m_appselfservice_user WHERE username = " + sqlLiteral(username));
  }

  /**
   * Inserts a self-service user whose password is "password". With {@code withBackOfficeTwin} a
   * different back-office user (another username) gets the very same id, which is what two
   * independent id sequences produce in practice.
   */
  private static String insertSelfServiceUser(boolean withBackOfficeTwin) {
    Integer clientId = createClient();
    String username = "sspw_" + UUID.randomUUID().toString().substring(0, 8);
    String twinSql =
        withBackOfficeTwin
            ? """
            INSERT INTO m_appuser(
                id, office_id, username, password, email, firstname, lastname, is_deleted,
                nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining
            )
            SELECT id, %1$s, %2$s, 'victim-hash-that-must-not-change', %3$s,
                'Back', 'Office', false, true, true, true, true, false
            FROM new_id;
            """
                .formatted(
                    sqlLiteral(officeId),
                    sqlLiteral("bo_" + username),
                    sqlLiteral("bo_" + username + "@fineract.org"))
            : "";
    executeSqlInPostgres(
        """
        CREATE TEMP TABLE new_id ON COMMIT DROP AS
        SELECT GREATEST(
            COALESCE((SELECT MAX(id) FROM m_appuser), 0),
            COALESCE((SELECT MAX(id) FROM m_appselfservice_user), 0)
        ) + 1 AS id;

        %1$s

        INSERT INTO m_appselfservice_user(
            id, office_id, username, password, email, firstname, lastname, is_deleted,
            nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining,
            password_never_expires, is_self_service_user, password_reset_required
        )
        SELECT id, %2$s, %3$s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1),
            %4$s, 'SS', 'PASSWORD', false, true, true, true, true, false, true, true, false
        FROM new_id;

        INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
        SELECT id, %5$s FROM new_id;

        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %6$s FROM new_id;

        SELECT setval(
            pg_get_serial_sequence('m_appselfservice_user', 'id'),
            (SELECT MAX(id) FROM m_appselfservice_user)
        );
        SELECT setval(
            pg_get_serial_sequence('m_appuser', 'id'),
            GREATEST(
                (SELECT MAX(id) FROM m_appuser),
                (SELECT MAX(id) FROM m_appselfservice_user)
            )
        );
        """
            .formatted(
                twinSql,
                sqlLiteral(officeId),
                sqlLiteral(username),
                sqlLiteral(username + "@fineract.org"),
                sqlLiteral(roleId),
                sqlLiteral(clientId)));
    return username;
  }
}
