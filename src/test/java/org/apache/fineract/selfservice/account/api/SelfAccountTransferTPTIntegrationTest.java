/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelfAccountTransferTPTIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH);

  private static final String ACCOUNT_TRANSFERS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/accounttransfers";
  private static final String BENEFICIARIES_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/self/beneficiaries/tpt";
  private static final String ADMIN_SAVINGS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/savingsaccounts";
  private static final String ADMIN_CLIENTS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/clients";
  private static final String ADMIN_ROLES_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/roles";
  private static final String ADMIN_CONFIGURATIONS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/configurations";

  @Test
  @DisplayName("POST /self/accounttransfers?type=tpt moves funds and returns 200")
  void transferToThirdParty_movesExpectedFunds_returns200() {
    String today = LocalDate.now(ZoneId.of("UTC")).format(FORMATTER);

    Boolean originalTptLimit = null;
    Boolean originalPermission = null;
    RuntimeException cleanupFailure = null;

    try {
      originalTptLimit = disableDailyTptLimit();
      originalPermission = grantTransferPermissionToSelfServiceRole();

      SeedResult sender = seedClientWithFundedSavings("sender", today);
      SeedResult receiver = seedClientWithActiveSavings("receiver", today);

      String ssUsername = insertSelfServiceUserDirectly(sender.clientId(), sender.officeId());

      String receiverAccountNumber =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
              .get(ADMIN_SAVINGS_PATH + "/" + receiver.savingsId())
              .then()
              .statusCode(200)
              .extract()
              .path("accountNo");

      Map<String, Object> beneficiaryBody = new HashMap<>();
      beneficiaryBody.put("locale", "en");
      beneficiaryBody.put("name", "Receiver-" + receiver.clientId());
      beneficiaryBody.put("officeName", "Head Office");
      beneficiaryBody.put("accountNumber", receiverAccountNumber);
      beneficiaryBody.put("accountType", 2);
      beneficiaryBody.put("transferLimit", 10000);

      Integer beneficiaryId =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
              .body(beneficiaryBody)
              .post(BENEFICIARIES_PATH)
              .then()
              .statusCode(200)
              .extract()
              .path("resourceId");

      given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
          .get(BENEFICIARIES_PATH)
          .then()
          .statusCode(200)
          .body(
              "find { it.id == " + beneficiaryId + " }.name",
              equalTo("Receiver-" + receiver.clientId()));

      Map<String, Object> transferBody = new HashMap<>();
      transferBody.put("fromOfficeId", sender.officeId());
      transferBody.put("fromClientId", sender.clientId());
      transferBody.put("fromAccountId", sender.savingsId());
      transferBody.put("fromAccountType", 2);
      transferBody.put("toOfficeId", receiver.officeId());
      transferBody.put("toClientId", receiver.clientId());
      transferBody.put("toAccountId", receiver.savingsId());
      transferBody.put("toAccountType", 2);
      transferBody.put("transferAmount", "500.00");
      transferBody.put("transferDate", today);
      transferBody.put("transferDescription", "MX-234 integration test transfer");
      transferBody.put("dateFormat", DATE_FORMAT);
      transferBody.put("locale", "en");

      Response transferResponse =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
              .body(transferBody)
              .post(ACCOUNT_TRANSFERS_PATH + "?type=tpt")
              .then()
              .extract()
              .response();

      Assertions.assertEquals(
          200,
          transferResponse.statusCode(),
          "Transfer failed: " + transferResponse.body().asString());

      Float senderBalance =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
              .get(SelfServiceTestUtils.SELF_SAVINGS_PATH + "/" + sender.savingsId())
              .then()
              .statusCode(200)
              .extract()
              .path("summary.accountBalance");

      Assertions.assertEquals(
          0,
          new BigDecimal("500.00").compareTo(BigDecimal.valueOf(senderBalance)),
          "Sender balance should be 500.00 after transfer but was: " + senderBalance);

      Float receiverBalance =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
              .get(ADMIN_SAVINGS_PATH + "/" + receiver.savingsId())
              .then()
              .statusCode(200)
              .extract()
              .path("summary.accountBalance");

      Assertions.assertEquals(
          0,
          new BigDecimal("500.00").compareTo(BigDecimal.valueOf(receiverBalance)),
          "Receiver balance should be 500.00 after transfer but was: " + receiverBalance);
    } finally {
      try {
        restoreDailyTptLimit(originalTptLimit);
      } catch (RuntimeException ex) {
        cleanupFailure = ex;
      }
      try {
        restoreTransferPermission(originalPermission);
      } catch (RuntimeException ex) {
        if (cleanupFailure != null) {
          cleanupFailure.addSuppressed(ex);
        } else {
          cleanupFailure = ex;
        }
      }
      if (cleanupFailure != null) {
        throw cleanupFailure;
      }
    }
  }

  @Test
  @DisplayName(
      "POST /self/accounttransfers?type=tpt returns 403 when amount exceeds beneficiary limit")
  void transferToThirdParty_exceedsBeneficiaryLimit_returns403() {
    String today = LocalDate.now(ZoneId.of("UTC")).format(FORMATTER);

    Boolean originalTptLimit = null;
    Boolean originalPermission = null;
    RuntimeException cleanupFailure = null;

    try {
      originalTptLimit = disableDailyTptLimit();
      originalPermission = grantTransferPermissionToSelfServiceRole();

      SeedResult sender = seedClientWithFundedSavings("sender", today);
      SeedResult receiver = seedClientWithActiveSavings("receiver", today);

      String ssUsername = insertSelfServiceUserDirectly(sender.clientId(), sender.officeId());

      String receiverAccountNumber =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
              .get(ADMIN_SAVINGS_PATH + "/" + receiver.savingsId())
              .then()
              .statusCode(200)
              .extract()
              .path("accountNo");

      Map<String, Object> beneficiaryBody = new HashMap<>();
      beneficiaryBody.put("locale", "en");
      beneficiaryBody.put("name", "ReceiverLimit-" + receiver.clientId());
      beneficiaryBody.put("officeName", "Head Office");
      beneficiaryBody.put("accountNumber", receiverAccountNumber);
      beneficiaryBody.put("accountType", 2);
      beneficiaryBody.put("transferLimit", 100); // 100 limit!

      given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
          .body(beneficiaryBody)
          .post(BENEFICIARIES_PATH)
          .then()
          .statusCode(200);

      Map<String, Object> transferBody = new HashMap<>();
      transferBody.put("fromOfficeId", sender.officeId());
      transferBody.put("fromClientId", sender.clientId());
      transferBody.put("fromAccountId", sender.savingsId());
      transferBody.put("fromAccountType", 2);
      transferBody.put("toOfficeId", receiver.officeId());
      transferBody.put("toClientId", receiver.clientId());
      transferBody.put("toAccountId", receiver.savingsId());
      transferBody.put("toAccountType", 2);
      transferBody.put("transferAmount", "500.00"); // 500 is greater than 100 limit
      transferBody.put("transferDate", today);
      transferBody.put("transferDescription", "Exceeding limit test");
      transferBody.put("dateFormat", DATE_FORMAT);
      transferBody.put("locale", "en");

      given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
          .body(transferBody)
          .post(ACCOUNT_TRANSFERS_PATH + "?type=tpt")
          .then()
          .statusCode(
              403); // Fineract limit exceptions are PlatformDomainRule exceptions generally mapped
      // to 403 Forbidden

      Float senderBalance =
          given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), ssUsername, "password"))
              .get(SelfServiceTestUtils.SELF_SAVINGS_PATH + "/" + sender.savingsId())
              .then()
              .statusCode(200)
              .extract()
              .path("summary.accountBalance");

      Assertions.assertEquals(
          0,
          new BigDecimal("1000.00").compareTo(BigDecimal.valueOf(senderBalance)),
          "Sender balance should remain 1000.00 after failed transfer. Was: " + senderBalance);

    } finally {
      try {
        restoreDailyTptLimit(originalTptLimit);
      } catch (RuntimeException ex) {
        cleanupFailure = ex;
      }
      try {
        restoreTransferPermission(originalPermission);
      } catch (RuntimeException ex) {
        if (cleanupFailure != null) {
          cleanupFailure.addSuppressed(ex);
        } else {
          cleanupFailure = ex;
        }
      }
      if (cleanupFailure != null) {
        throw cleanupFailure;
      }
    }
  }

  private Boolean disableDailyTptLimit() {
    JsonPath configs =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_CONFIGURATIONS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    List<Map<String, Object>> configList = configs.getList("globalConfiguration");
    Integer configId = null;
    Boolean originalEnabled = false;
    for (Map<String, Object> cfg : configList) {
      if ("daily-tpt-limit".equals(cfg.get("name"))) {
        configId = (Integer) cfg.get("id");
        originalEnabled = (Boolean) cfg.get("enabled");
        break;
      }
    }

    if (configId != null) {
      Map<String, Object> body = new HashMap<>();
      body.put("enabled", false);
      given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
          .body(body)
          .put(ADMIN_CONFIGURATIONS_PATH + "/" + configId)
          .then()
          .statusCode(200);
    }
    return originalEnabled;
  }

  private void restoreDailyTptLimit(Boolean originalEnabled) {
    if (originalEnabled == null) return;
    JsonPath configs =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_CONFIGURATIONS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    List<Map<String, Object>> configList = configs.getList("globalConfiguration");
    Integer configId = null;
    for (Map<String, Object> cfg : configList) {
      if ("daily-tpt-limit".equals(cfg.get("name"))) {
        configId = (Integer) cfg.get("id");
        break;
      }
    }

    if (configId != null) {
      Map<String, Object> body = new HashMap<>();
      body.put("enabled", originalEnabled);
      given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
          .body(body)
          .put(ADMIN_CONFIGURATIONS_PATH + "/" + configId)
          .then()
          .statusCode(200);
    }
  }

  private Boolean grantTransferPermissionToSelfServiceRole() {
    Integer roleId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_ROLES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .path(
                "find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");

    Boolean originalPermission =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_ROLES_PATH + "/" + roleId + "/permissions")
            .then()
            .statusCode(200)
            .extract()
            .path("permissionUsageData.find { it.code == 'CREATE_ACCOUNTTRANSFER' }.selected");

    Map<String, Object> body = new HashMap<>();
    body.put("permissions", Map.of("CREATE_ACCOUNTTRANSFER", true));
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .put(ADMIN_ROLES_PATH + "/" + roleId + "/permissions")
        .then()
        .statusCode(200);

    return originalPermission;
  }

  private void restoreTransferPermission(Boolean originalPermission) {
    if (originalPermission == null) {
      originalPermission = false;
    }

    Integer roleId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_ROLES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .path(
                "find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");

    Map<String, Object> body = new HashMap<>();
    body.put("permissions", Map.of("CREATE_ACCOUNTTRANSFER", originalPermission));
    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .put(ADMIN_ROLES_PATH + "/" + roleId + "/permissions")
        .then()
        .statusCode(200);
  }

  private SeedResult seedClientWithFundedSavings(String label, String today) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> clientResp = createClientAndExtract(label, suffix, today);
    Integer clientId = (Integer) clientResp.get("clientId");
    Integer officeId = (Integer) clientResp.get("officeId");

    Integer productId = createZeroFeeSavingsProduct(suffix);
    Integer savingsId = openSavingsAccount(clientId, productId, today);
    approveSavingsAccount(savingsId, today);
    activateSavingsAccount(savingsId, today);

    depositToSavingsAccount(savingsId, new BigDecimal("1000.00"), today);

    return new SeedResult(clientId, officeId, savingsId);
  }

  private SeedResult seedClientWithActiveSavings(String label, String today) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> clientResp = createClientAndExtract(label, suffix, today);
    Integer clientId = (Integer) clientResp.get("clientId");
    Integer officeId = (Integer) clientResp.get("officeId");

    Integer productId = createZeroFeeSavingsProduct(suffix);
    Integer savingsId = openSavingsAccount(clientId, productId, today);
    approveSavingsAccount(savingsId, today);
    activateSavingsAccount(savingsId, today);

    return new SeedResult(clientId, officeId, savingsId);
  }

  private Map<String, Object> createClientAndExtract(String label, String suffix, String today) {
    Map<String, Object> body = new HashMap<>();
    body.put("officeId", 1);
    body.put("legalFormId", 1);
    body.put("firstname", label);
    body.put("lastname", suffix);
    body.put("externalId", label + "-" + suffix);
    body.put("dateFormat", DATE_FORMAT);
    body.put("locale", "en_GB");
    body.put("active", true);
    body.put("activationDate", today);

    Response resp =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .body(body)
            .post(ADMIN_CLIENTS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .response();

    Integer clientId = resp.jsonPath().getInt("clientId");

    Integer officeId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_CLIENTS_PATH + "/" + clientId)
            .then()
            .statusCode(200)
            .extract()
            .path("officeId");

    Map<String, Object> result = new HashMap<>();
    result.put("clientId", clientId);
    result.put("officeId", officeId);
    return result;
  }

  private Integer createZeroFeeSavingsProduct(String suffix) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "TPT-Product-" + suffix);
    body.put("shortName", suffix.substring(0, 4));
    body.put("description", "MX-234 zero-fee savings product");
    body.put("currencyCode", "USD");
    body.put("digitsAfterDecimal", "4");
    body.put("inMultiplesOf", "0");
    body.put("locale", "en_GB");
    body.put("nominalAnnualInterestRate", "0");
    body.put("interestCalculationType", "1");
    body.put("interestCalculationDaysInYearType", "365");
    body.put("interestCompoundingPeriodType", "4");
    body.put("interestPostingPeriodType", "4");
    body.put("accountingRule", "1");
    body.put("charges", List.of());

    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/savingsproducts")
        .then()
        .statusCode(200)
        .extract()
        .path("resourceId");
  }

  private Integer openSavingsAccount(Integer clientId, Integer productId, String today) {
    Map<String, Object> body = new HashMap<>();
    body.put("clientId", clientId);
    body.put("productId", productId);
    body.put("locale", "en_GB");
    body.put("dateFormat", DATE_FORMAT);
    body.put("submittedOnDate", today);

    return given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(ADMIN_SAVINGS_PATH)
        .then()
        .statusCode(200)
        .extract()
        .path("savingsId");
  }

  private void approveSavingsAccount(Integer savingsId, String today) {
    Map<String, Object> body = new HashMap<>();
    body.put("locale", "en");
    body.put("dateFormat", DATE_FORMAT);
    body.put("approvedOnDate", today);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(ADMIN_SAVINGS_PATH + "/" + savingsId + "?command=approve")
        .then()
        .statusCode(200);
  }

  private void activateSavingsAccount(Integer savingsId, String today) {
    Map<String, Object> body = new HashMap<>();
    body.put("locale", "en");
    body.put("dateFormat", DATE_FORMAT);
    body.put("activatedOnDate", today);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(ADMIN_SAVINGS_PATH + "/" + savingsId + "?command=activate")
        .then()
        .statusCode(200);
  }

  private void depositToSavingsAccount(Integer savingsId, BigDecimal amount, String today) {
    Map<String, Object> body = new HashMap<>();
    body.put("locale", "en");
    body.put("dateFormat", DATE_FORMAT);
    body.put("transactionDate", today);
    body.put("transactionAmount", amount);
    body.put("paymentTypeId", 1);

    given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .body(body)
        .post(ADMIN_SAVINGS_PATH + "/" + savingsId + "/transactions?command=deposit")
        .then()
        .statusCode(200);
  }

  private String insertSelfServiceUserDirectly(Integer clientId, Integer officeId) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "sstpt_" + suffix;

    Integer roleId =
        given(SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
            .get(ADMIN_ROLES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .path(
                "find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");

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
                %s, %s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1), %s,
                'SS', 'TPT', false, true, true, true, true, false
            )
            RETURNING id
        ), appuser_role AS (
            INSERT INTO m_appuser_role(appuser_id, role_id)
            SELECT id, %s FROM new_appuser
        ), new_self_user AS (
            INSERT INTO m_appselfservice_user(
                id, office_id, username, password, email, firstname, lastname, is_deleted,
                nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining,
                password_never_expires, is_self_service_user, password_reset_required
            )
            SELECT id, %s, %s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1), %s,
                'SS', 'TPT', false, true, true, true, true, false, true, true, false
            FROM new_appuser
            RETURNING id
        ), self_user_role AS (
            INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
            SELECT id, %s FROM new_self_user
        )
        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %s FROM new_self_user;

        SELECT setval(
            pg_get_serial_sequence('m_appselfservice_user', 'id'),
            (SELECT MAX(id) FROM m_appselfservice_user)
        );
        """,
        officeId,
        username,
        username + "@fineract.org",
        roleId,
        officeId,
        username,
        username + "@fineract.org",
        roleId,
        clientId);

    return username;
  }

  private record SeedResult(Integer clientId, Integer officeId, Integer savingsId) {}
}
