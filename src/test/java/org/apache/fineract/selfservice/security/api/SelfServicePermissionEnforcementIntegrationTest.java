/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class SelfServicePermissionEnforcementIntegrationTest
    extends SelfServiceIntegrationTestBase {

  @Test
  @DisplayName(
      "Verify that Self-Service Users strictly require explicit READ_SAVINGSPRODUCT grant to access /v1/self/savingsproducts")
  void testSavingsProductsRequireReadSavingsProductPermission() {

    // 1. Create a Client
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

    // 2. Clear ALL_FUNCTIONS and ALL_FUNCTIONS_READ from 'Self Service User' role, and ensure
    // READ_SAVINGSPRODUCT is false
    Response getRolesResponse =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles");
    Integer roleId =
        getRolesResponse.jsonPath().getInt("find { it.name == 'Self Service User' }.id");

    Map<String, Object> permissions = new HashMap<>();
    permissions.put("ALL_FUNCTIONS", false);
    permissions.put("ALL_FUNCTIONS_READ", false);
    permissions.put("READ_SAVINGSPRODUCT", false);

    Map<String, Object> permissionBody = new HashMap<>();
    permissionBody.put("permissions", permissions);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(permissionBody)
        .put(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles/" + roleId + "/permissions")
        .then()
        .statusCode(200);

    // 3. Seed AppSelfServiceUser directly via SQL (self-service table only)
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
                'Tomas', 'Test', false, true, true, true, true, false, true, true, false
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

    // 4. Test the API without the permission: Expect 403
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
        .when()
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/savingsproducts")
        .then()
        .statusCode(403);

    // 4b. Even if ALL_FUNCTIONS is set, self-service must still require explicit
    // READ_SAVINGSPRODUCT
    permissions.clear();
    permissions.put("ALL_FUNCTIONS", true);
    permissions.put("ALL_FUNCTIONS_READ", true);
    permissions.put("READ_SAVINGSPRODUCT", false);
    permissionBody.put("permissions", permissions);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(permissionBody)
        .put(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles/" + roleId + "/permissions")
        .then()
        .statusCode(200);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
        .when()
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/savingsproducts")
        .then()
        .statusCode(403);

    // 5. Grant READ_SAVINGSPRODUCT
    permissions.clear();
    permissions.put("READ_SAVINGSPRODUCT", true);
    permissionBody.put("permissions", permissions);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(permissionBody)
        .put(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles/" + roleId + "/permissions")
        .then()
        .statusCode(200);

    // 6. Test the API with the permission: Expect 200
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
        .when()
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/savingsproducts")
        .then()
        .statusCode(200);

    // 7. Revoke READ_SAVINGSPRODUCT to verify the cache does not retain it
    permissions.put("READ_SAVINGSPRODUCT", false);
    permissionBody.put("permissions", permissions);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(permissionBody)
        .put(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles/" + roleId + "/permissions")
        .then()
        .statusCode(200);

    // 8. Test the API without the permission: Expect 403 immediately
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), username, "password"))
        .when()
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/savingsproducts")
        .then()
        .statusCode(403);
  }
}
