/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

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

/** E2E tests for self-service beneficiary TPT operations (add, update, delete). */
public class SelfBeneficiaryTPTIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String BENEFICIARIES_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/beneficiaries/tpt";

  private SeedResult seedSelfServiceUserAndSavingsAccount() {
    String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    Integer clientId = createClient(uniqueSuffix);
    Integer productId = createSavingsProduct(uniqueSuffix);
    Integer savingsId = openSavingsAccount(clientId, productId);
    String accountNumber = activateAndGetSavingsAccountNumber(savingsId);

    Integer roleId = getSelfServiceRoleId();
    String username = insertSelfServiceUserDirectly(uniqueSuffix, clientId, roleId);

    return new SeedResult(username, accountNumber);
  }

  private Integer createClient(String uniqueSuffix) {
    Map<String, Object> body = new HashMap<>();
    body.put("officeId", 1);
    body.put("legalFormId", 1);
    body.put("firstname", "Beneficiary");
    body.put("lastname", uniqueSuffix);
    body.put("externalId", uniqueSuffix);
    body.put("dateFormat", "dd MMMM yyyy");
    body.put("locale", "en_GB");
    body.put("active", true);
    body.put("activationDate", "01 January 2026");

    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/clients")
        .then()
        .statusCode(200)
        .extract()
        .path("clientId");
  }

  private Integer createSavingsProduct(String uniqueSuffix) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "SS-Product-" + uniqueSuffix);
    body.put("shortName", uniqueSuffix.substring(0, 4));
    body.put("description", "Integration test savings product");
    body.put("currencyCode", "USD");
    body.put("digitsAfterDecimal", "4");
    body.put("inMultiplesOf", "0");
    body.put("locale", "en_GB");
    body.put("nominalAnnualInterestRate", "10.0");
    body.put("interestCalculationType", "1");
    body.put("interestCalculationDaysInYearType", "365");
    body.put("interestCompoundingPeriodType", "4");
    body.put("interestPostingPeriodType", "4");
    body.put("accountingRule", "1");

    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/savingsproducts")
        .then()
        .statusCode(200)
        .extract()
        .path("resourceId");
  }

  private Integer openSavingsAccount(Integer clientId, Integer productId) {
    Map<String, Object> savingsBody = new HashMap<>();
    savingsBody.put("clientId", clientId);
    savingsBody.put("productId", productId);
    savingsBody.put("locale", "en_GB");
    savingsBody.put("dateFormat", "dd MMMM yyyy");
    savingsBody.put("submittedOnDate", "01 January 2026");

    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(savingsBody)
        .post(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/savingsaccounts")
        .then()
        .statusCode(200)
        .extract()
        .path("savingsId");
  }

  private String activateAndGetSavingsAccountNumber(Integer savingsId) {
    Map<String, Object> approveBody = new HashMap<>();
    approveBody.put("locale", "en");
    approveBody.put("dateFormat", "dd MMMM yyyy");
    approveBody.put("approvedOnDate", "01 January 2026");
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(approveBody)
        .post(
            SelfServiceTestUtils.CONTEXT_PATH
                + "/api/v1/savingsaccounts/"
                + savingsId
                + "?command=approve")
        .then()
        .statusCode(200);

    Map<String, Object> activateBody = new HashMap<>();
    activateBody.put("locale", "en");
    activateBody.put("dateFormat", "dd MMMM yyyy");
    activateBody.put("activatedOnDate", "01 January 2026");
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(activateBody)
        .post(
            SelfServiceTestUtils.CONTEXT_PATH
                + "/api/v1/savingsaccounts/"
                + savingsId
                + "?command=activate")
        .then()
        .statusCode(200);

    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/savingsaccounts/" + savingsId)
        .then()
        .statusCode(200)
        .extract()
        .path("accountNo");
  }

  private Integer getSelfServiceRoleId() {
    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles")
        .then()
        .statusCode(200)
        .extract()
        .path("find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");
  }

  private String insertSelfServiceUserDirectly(
      String uniqueSuffix, Integer clientId, Integer roleId) {
    String username = "ssuser_" + uniqueSuffix;

    executeSqlInPostgres(
        """
        SELECT setval(
            pg_get_serial_sequence('m_appuser', 'id'),
            GREATEST(
                COALESCE((SELECT MAX(id) FROM m_appuser), 0),
                COALESCE((SELECT MAX(id) FROM m_appselfservice_user), 0)
            )
        );

        WITH new_appuser AS (
            INSERT INTO m_appuser(
                office_id, username, password, email, firstname, lastname, is_deleted,
                nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining
            )
            VALUES (
                1, %s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1), %s,
                'Beneficiary', 'User', false, true, true, true, true, false
            )
            RETURNING id
        ), appuser_role AS (
            INSERT INTO m_appuser_role(appuser_id, role_id)
            SELECT id, %d FROM new_appuser
        ), new_self_user AS (
            INSERT INTO m_appselfservice_user(
                id, office_id, username, password, email, firstname, lastname, is_deleted,
                nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining,
                password_never_expires, is_self_service_user, password_reset_required
            )
            SELECT id, 1, %s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1), %s,
                'Beneficiary', 'User', false, true, true, true, true, false, true, true, false
            FROM new_appuser
            RETURNING id
        ), self_user_role AS (
            INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
            SELECT id, %d FROM new_self_user
        )
        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %d FROM new_self_user;

        SELECT setval(
            pg_get_serial_sequence('m_appselfservice_user', 'id'),
            (SELECT MAX(id) FROM m_appselfservice_user)
        );
        """
            .formatted(
                sqlLiteral(username),
                sqlLiteral(username + "@fineract.org"),
                roleId,
                sqlLiteral(username),
                sqlLiteral(username + "@fineract.org"),
                roleId,
                clientId));

    return username;
  }

  /** Tests successfully adding a beneficiary using a self-service user context. */
  @Test
  @DisplayName("POST /self/beneficiaries/tpt returns 200 for self-service user")
  void addBeneficiary_selfServiceUser_returns200() {
    SeedResult seed = seedSelfServiceUserAndSavingsAccount();

    Map<String, Object> body = new HashMap<>();
    body.put("locale", "en");
    body.put("name", "Test Beneficiary");
    body.put("officeName", "Head Office");
    body.put("accountNumber", seed.accountNumber());
    body.put("accountType", 2);
    body.put("transferLimit", 500);

    Response response =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(body)
            .post(BENEFICIARIES_PATH)
            .then()
            .extract()
            .response();

    Assertions.assertEquals(
        200,
        response.statusCode(),
        "Expected 200 but got: " + response.statusCode() + ". Body: " + response.body().asString());
    Integer resourceId = response.jsonPath().getInt("resourceId");
    Assertions.assertNotNull(resourceId, "resourceId should be present in response");
  }

  /** Tests successfully updating an existing beneficiary using a self-service user context. */
  @Test
  @DisplayName("PUT /self/beneficiaries/tpt/{id} returns 200 for self-service user")
  void updateBeneficiary_selfServiceUser_returns200() {
    SeedResult seed = seedSelfServiceUserAndSavingsAccount();

    Map<String, Object> addBody = new HashMap<>();
    addBody.put("locale", "en");
    addBody.put("name", "Update Test Beneficiary");
    addBody.put("officeName", "Head Office");
    addBody.put("accountNumber", seed.accountNumber());
    addBody.put("accountType", 2);
    addBody.put("transferLimit", 500);

    Integer beneficiaryId =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(addBody)
            .post(BENEFICIARIES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .path("resourceId");

    Map<String, Object> updateBody = new HashMap<>();
    updateBody.put("name", "Updated Name");
    updateBody.put("transferLimit", 1000);

    Response updateResponse =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(updateBody)
            .put(BENEFICIARIES_PATH + "/" + beneficiaryId)
            .then()
            .extract()
            .response();

    Assertions.assertEquals(
        200,
        updateResponse.statusCode(),
        "PUT returned: "
            + updateResponse.statusCode()
            + ". Body: "
            + updateResponse.body().asString());

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), seed.username(), "password"))
        .get(BENEFICIARIES_PATH)
        .then()
        .statusCode(200)
        .body(
            "find { it.id == " + beneficiaryId + " }.name",
            org.hamcrest.Matchers.equalTo("Updated Name"))
        .body(
            "find { it.id == " + beneficiaryId + " }.transferLimit",
            org.hamcrest.Matchers.equalTo(1000));
  }

  /** Tests successfully deleting a beneficiary using a self-service user context. */
  @Test
  @DisplayName("DELETE /self/beneficiaries/tpt/{id} returns 200 for self-service user")
  void deleteBeneficiary_selfServiceUser_returns200() {
    SeedResult seed = seedSelfServiceUserAndSavingsAccount();

    Map<String, Object> addBody = new HashMap<>();
    addBody.put("locale", "en");
    addBody.put("name", "Delete Test Beneficiary");
    addBody.put("officeName", "Head Office");
    addBody.put("accountNumber", seed.accountNumber());
    addBody.put("accountType", 2);
    addBody.put("transferLimit", 500);

    Integer beneficiaryId =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(addBody)
            .post(BENEFICIARIES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .path("resourceId");

    Response deleteResponse =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .delete(BENEFICIARIES_PATH + "/" + beneficiaryId)
            .then()
            .extract()
            .response();

    Assertions.assertEquals(
        200,
        deleteResponse.statusCode(),
        "DELETE returned: "
            + deleteResponse.statusCode()
            + ". Body: "
            + deleteResponse.body().asString());

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), seed.username(), "password"))
        .get(BENEFICIARIES_PATH)
        .then()
        .statusCode(200)
        .body("find { it.id == " + beneficiaryId + " }", org.hamcrest.Matchers.nullValue());
  }

  private record SeedResult(String username, String accountNumber) {}
}
