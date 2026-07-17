/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.pockets.api;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E2E tests for self-service pocket operations (link, retrieve, delink). */
public class SelfPocketApiIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String POCKETS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/pockets";

  private SeedResult seedSelfServiceUserAndSavingsAccount() {
    String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    Integer clientId = createClient(uniqueSuffix);
    Integer productId = createSavingsProduct(uniqueSuffix);
    Integer savingsId = openAndActivateSavingsAccount(clientId, productId);

    Integer roleId = getSelfServiceRoleId();
    String username = insertSelfServiceUserDirectly(uniqueSuffix, clientId, roleId);

    return new SeedResult(username, savingsId);
  }

  private Integer createClient(String uniqueSuffix) {
    Map<String, Object> body = new HashMap<>();
    body.put("officeId", 1);
    body.put("legalFormId", 1);
    body.put("firstname", "Pocket");
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

  private Integer openAndActivateSavingsAccount(Integer clientId, Integer productId) {
    Map<String, Object> savingsBody = new HashMap<>();
    savingsBody.put("clientId", clientId);
    savingsBody.put("productId", productId);
    savingsBody.put("locale", "en_GB");
    savingsBody.put("dateFormat", "dd MMMM yyyy");
    savingsBody.put("submittedOnDate", "01 January 2026");

    Integer savingsId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .body(savingsBody)
            .post(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/savingsaccounts")
            .then()
            .statusCode(200)
            .extract()
            .path("savingsId");

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

    return savingsId;
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
                'Pocket', 'User', false, true, true, true, true, false
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
                'Pocket', 'User', false, true, true, true, true, false, true, true, false
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

  /** Full lifecycle: link a savings account, retrieve it, then delink it. */
  @Test
  @DisplayName("Self-service pocket link/retrieve/delink lifecycle returns 200")
  void linkRetrieveDelink_selfServiceUser_lifecycle() {
    SeedResult seed = seedSelfServiceUserAndSavingsAccount();

    // 1. Link the savings account to the pocket.
    Map<String, Object> accountDetail = new HashMap<>();
    accountDetail.put("accountId", seed.savingsId());
    accountDetail.put("accountType", "SAVINGS");
    Map<String, Object> linkBody = new HashMap<>();
    linkBody.put("accountsDetail", List.of(accountDetail));

    Response linkResponse =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(linkBody)
            .post(POCKETS_PATH + "?command=linkAccounts")
            .then()
            .extract()
            .response();

    Assertions.assertEquals(
        200,
        linkResponse.statusCode(),
        "Link expected 200 but got: "
            + linkResponse.statusCode()
            + ". Body: "
            + linkResponse.body().asString());
    Assertions.assertNotNull(
        linkResponse.jsonPath().getInt("resourceId"), "resourceId should be present");

    // 2. Retrieve accounts linked to the pocket and confirm the savings account is present.
    Response getResponse =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .get(POCKETS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .response();

    Integer mappingId =
        getResponse
            .jsonPath()
            .getInt("savingsAccounts.find { it.accountId == " + seed.savingsId() + " }.id");
    Assertions.assertNotNull(
        mappingId,
        "Linked savings account should be present in pocket. Body: "
            + getResponse.body().asString());

    // 3. Delink the account using the returned mapping id.
    Map<String, Object> delinkBody = new HashMap<>();
    delinkBody.put("pocketAccountMappingIds", List.of(mappingId));

    Response delinkResponse =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(delinkBody)
            .post(POCKETS_PATH + "?command=delinkAccounts")
            .then()
            .extract()
            .response();

    Assertions.assertEquals(
        200,
        delinkResponse.statusCode(),
        "Delink expected 200 but got: "
            + delinkResponse.statusCode()
            + ". Body: "
            + delinkResponse.body().asString());

    // 4. Confirm the account is no longer linked.
    String afterDelinkBody =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .get(POCKETS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

    Assertions.assertFalse(
        afterDelinkBody.contains("\"accountId\":" + seed.savingsId()),
        "Savings account should have been delinked. Body: " + afterDelinkBody);
  }

  /** Linking an account the self-service user does not own is rejected. */
  @Test
  @DisplayName("Linking a non-owned account is rejected")
  void linkAccounts_notOwnedByUser_isRejected() {
    SeedResult seed = seedSelfServiceUserAndSavingsAccount();

    Map<String, Object> accountDetail = new HashMap<>();
    accountDetail.put("accountId", 999999);
    accountDetail.put("accountType", "SAVINGS");
    Map<String, Object> linkBody = new HashMap<>();
    linkBody.put("accountsDetail", List.of(accountDetail));

    Response response =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(linkBody)
            .post(POCKETS_PATH + "?command=linkAccounts")
            .then()
            .extract()
            .response();

    Assertions.assertNotEquals(
        200,
        response.statusCode(),
        "Linking a non-owned account should not succeed. Body: " + response.body().asString());
  }

  /** An unrecognised command query parameter is rejected. */
  @Test
  @DisplayName("Unknown pocket command is rejected")
  void handleCommands_unknownCommand_isRejected() {
    SeedResult seed = seedSelfServiceUserAndSavingsAccount();

    Response response =
        given(
                SelfServiceTestUtils.requestSpecWithAuth(
                    getFineractPort(), seed.username(), "password"))
            .body(Map.of("accountsDetail", List.of()))
            .post(POCKETS_PATH + "?command=bogusCommand")
            .then()
            .extract()
            .response();

    Assertions.assertNotEquals(
        200,
        response.statusCode(),
        "Unknown command should be rejected. Body: " + response.body().asString());
  }

  private record SeedResult(String username, Integer savingsId) {}
}
