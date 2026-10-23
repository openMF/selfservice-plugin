/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The activity history (US-E12-05, MX-491) against a real Fineract + PostgreSQL started by
 * Testcontainers with the plugin on the classpath.
 *
 * <p>All integration test classes share one Fineract container and one tenant database, so every
 * setting this class changes is restored in {@link #restoreDefaults()}.
 */
class SelfActivityIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH);
  private static final String CP = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String ACTIVITY = CP + "/self/activity";
  private static final String STEPUP = CP + "/self/stepup";
  private static final String TRANSFERS = CP + "/self/accounttransfers";
  private static final String BENEFICIARIES = CP + "/self/beneficiaries/tpt";
  private static final String FORWARDED_IP = "203.0.113.9";
  private static final String KNOWN_OTP = "123456";

  private static String today;
  private static Integer roleId;
  private static Account receiver;

  private record Account(Integer clientId, Integer officeId, Integer savingsId) {}

  private record Session(String username, long userId, String authHeader, Account account) {}

  @BeforeAll
  static void seed() {
    today = LocalDate.now(ZoneId.of("UTC")).format(FORMATTER);
    roleId =
        given(admin())
            .get(CP + "/roles")
            .then()
            .statusCode(200)
            .extract()
            .path(
                "find { it.name == '" + SelfServiceApiConstants.SELF_SERVICE_USER_ROLE + "' }.id");
    given(admin())
        .body(Map.of("permissions", Map.of("CREATE_ACCOUNTTRANSFER", true)))
        .put(CP + "/roles/" + roleId + "/permissions")
        .then()
        .statusCode(200);
    disableDailyTptLimit();
    receiver = seedClient("receiver", false);
    setActivityProperty("enabled", "true");
  }

  /** The tenant settings are shared with the other test classes: leave them as found. */
  @AfterAll
  static void restoreDefaults() {
    setActivityProperty("enabled", "true");
    setActivityProperty("maxPageSize", "100");
    setStepUpProperty("enabled", "false");
    setStepUpProperty("challengeRateLimit", "5");
    setStepUpProperty("hmacSecret", "change-me-before-enabling");
  }

  // ---------------------------------------------------------------------------------------------
  // logins
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a login shows up in the feed, with the IP of the connection, masked")
  void login_isListed_andTheIpIsMasked() {
    Session session = newUser(false);

    Response feed = awaitType(session, "LOGIN");

    Map<String, Object> login = firstOfType(feed, "LOGIN");
    assertEquals("SUCCESS", login.get("outcome"));
    assertNotNull(login.get("occurredAt"));
    assertTrue(
        String.valueOf(login.get("ipAddress")).matches("[0-9]+[.][0-9]+[.]x[.]x"),
        "masked address expected: " + login.get("ipAddress"));
  }

  @Test
  @DisplayName("the database never holds a full IP address")
  void theFullIpIsNeverStored() {
    Session session = newUser(false);
    awaitType(session, "LOGIN");

    String stored =
        querySingleValueInPostgres(
            "SELECT ip_masked FROM m_selfservice_activity WHERE appuser_id = "
                + session.userId()
                + " AND activity_type = 'LOGIN' ORDER BY id DESC LIMIT 1");

    assertTrue(stored.matches("[0-9]+[.][0-9]+[.]x[.]x"), stored);
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_activity WHERE ip_masked ~"
                + " '^[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+$'"));
  }

  @Test
  @DisplayName("wrong passwords are listed, but at most one per minute")
  void failedLogins_areListed_andThrottled() {
    Session session = newUser(false);

    for (int attempt = 0; attempt < 3; attempt++) {
      assertEquals(
          401,
          SelfServiceTestUtils.authenticate(
                  getFineractPort(), session.username(), "wrong-" + attempt)
              .statusCode());
    }

    Response feed = awaitType(session, "LOGIN_FAILED");
    long failures = types(feed).stream().filter("LOGIN_FAILED"::equals).count();
    assertEquals(1, failures, "three wrong passwords in a minute make one row");
    assertEquals("FAILURE", firstOfType(feed, "LOGIN_FAILED").get("outcome"));
  }

  // ---------------------------------------------------------------------------------------------
  // sensitive actions
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a password change is listed and no password appears anywhere")
  void passwordChange_isListed() {
    Session session = newUser(false);
    String newPassword = "Blue-Horizon7!";

    Response changed =
        given(sessionSpec(session))
            .body(Map.of("password", newPassword, "repeatPassword", newPassword))
            .put(CP + "/self/user");
    assertEquals(200, changed.statusCode(), changed.asString());

    // the change closes the old session: log in again to read the feed
    Session again = relogin(session, newPassword);
    Response feed = awaitType(again, "PASSWORD_CHANGED");
    assertEquals("SUCCESS", firstOfType(feed, "PASSWORD_CHANGED").get("outcome"));
    assertFalse(feed.asString().contains(newPassword));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_activity WHERE details LIKE '%Blue-Horizon7%'"));
  }

  @Test
  @DisplayName(
      "adding and removing a beneficiary are listed, with the name and without the account")
  void beneficiary_addedAndDeleted_areListed() {
    Session session = newUser(false);
    long beneficiaryId = addBeneficiary(session);

    Response added = awaitType(session, "BENEFICIARY_ADDED");
    Map<String, Object> entry = firstOfType(added, "BENEFICIARY_ADDED");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) entry.get("details");
    assertEquals("Receiver-" + receiver.clientId(), details.get("name"));
    assertFalse(
        added.asString().contains(receiverAccountNumber()), "the account number is not shown");

    assertEquals(
        200, given(sessionSpec(session)).delete(BENEFICIARIES + "/" + beneficiaryId).statusCode());
    assertEquals(
        "SUCCESS",
        firstOfType(awaitType(session, "BENEFICIARY_DELETED"), "BENEFICIARY_DELETED")
            .get("outcome"));
  }

  @Test
  @DisplayName("a confirmed transfer is listed with amount and currency, a refused one is not")
  void transfer_confirmed_isListed_refused_isNot() {
    Session session = newUser(true);

    assertEquals(200, tpt(session, "12.50", null).statusCode());
    Response feed = awaitType(session, "TRANSFER_CONFIRMED");
    @SuppressWarnings("unchecked")
    Map<String, Object> details =
        (Map<String, Object>) firstOfType(feed, "TRANSFER_CONFIRMED").get("details");
    assertEquals("12.50", details.get("amount"));
    // the legacy transfer event carries no currency: it is shown only when the event has one
    long before = types(feed).stream().filter("TRANSFER_CONFIRMED"::equals).count();

    // far more than the balance: the transfer is refused and rolled back
    Response refused = tpt(session, "999999999.00", null);
    assertTrue(refused.statusCode() >= 400, refused.asString());

    long after = types(feed(session, "")).stream().filter("TRANSFER_CONFIRMED"::equals).count();
    assertEquals(before, after, "a transfer that did not happen must not be in the history");
  }

  @Test
  @DisplayName("step-up: code requested, code verified and the confirmed transfer, in order")
  void stepUp_events_areListed() {
    try {
      setStepUpProperty("hmacSecret", STEP_UP_HMAC_SECRET);
      setStepUpProperty("enabled", "true");
      setStepUpProperty("challengeRateLimit", "50");
      Session session = newUser(true);

      long challengeId =
          given(sessionSpec(session))
              .body(challengeBody(session, "12.00"))
              .post(STEPUP + "/challenges")
              .then()
              .statusCode(201)
              .extract()
              .jsonPath()
              .getLong("challengeId");
      overwriteOtp(challengeId, KNOWN_OTP);
      String token =
          given(sessionSpec(session))
              .body(Map.of("otp", KNOWN_OTP))
              .post(STEPUP + "/challenges/" + challengeId + "/verify")
              .then()
              .statusCode(200)
              .extract()
              .path("stepUpToken");
      assertEquals(200, tpt(session, "12.00", token).statusCode());

      Response feed = awaitType(session, "TRANSFER_CONFIRMED");
      List<String> newestFirst = types(feed);
      assertTrue(
          newestFirst.indexOf("TRANSFER_CONFIRMED") < newestFirst.indexOf("STEP_UP_VERIFIED"),
          "newest first: " + newestFirst);
      assertTrue(
          newestFirst.indexOf("STEP_UP_VERIFIED") < newestFirst.indexOf("STEP_UP_REQUESTED"));
      @SuppressWarnings("unchecked")
      Map<String, Object> details =
          (Map<String, Object>) firstOfType(feed, "STEP_UP_REQUESTED").get("details");
      assertEquals("TPT_TRANSFER", details.get("action"));
      assertFalse(feed.asString().contains(KNOWN_OTP));
      assertFalse(feed.asString().contains(token));
    } finally {
      setStepUpProperty("enabled", "false");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // reading the feed
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a user only ever sees their own history")
  void feed_isPrivateToTheUser() {
    Session ana = newUser(false);
    Session luis = newUser(false);
    awaitType(ana, "LOGIN");
    awaitType(luis, "LOGIN");

    List<Integer> anaIds = feed(ana, "").path("pageItems.id");
    List<Integer> luisIds = feed(luis, "").path("pageItems.id");

    assertFalse(anaIds.isEmpty());
    assertFalse(luisIds.isEmpty());
    assertTrue(java.util.Collections.disjoint(anaIds, luisIds), "two users share no row");

    // there is no parameter that selects another user: an attempt is simply ignored
    List<Integer> triedToSpy =
        given(sessionSpec(ana))
            .queryParam("userId", luis.userId())
            .get(ACTIVITY)
            .path("pageItems.id");
    assertEquals(anaIds, triedToSpy);
  }

  @Test
  @DisplayName("paging, the type filter and bad input")
  void feed_pagingFilterAndValidation() {
    Session session = newUser(false);
    // every sign-in ends the previous session, so keep the newest one
    for (int i = 0; i < 3; i++) {
      session = relogin(session, "password");
    }
    awaitType(session, "LOGIN");

    Response all = feed(session, "");
    int total = all.path("totalFilteredRecords");
    assertTrue(total >= 4, "one login per sign-in: " + total);

    Response firstPage = feed(session, "limit=2&offset=0");
    Response secondPage = feed(session, "limit=2&offset=2");
    assertEquals(2, firstPage.<List<Object>>path("pageItems").size());
    assertEquals(total, (int) firstPage.path("totalFilteredRecords"));
    List<Integer> ids = new java.util.ArrayList<>(firstPage.<List<Integer>>path("pageItems.id"));
    ids.addAll(secondPage.<List<Integer>>path("pageItems.id"));
    assertEquals(ids.size(), ids.stream().distinct().count(), "pages must not overlap");
    assertEquals(
        ids.stream().sorted(java.util.Comparator.reverseOrder()).toList(), ids, "newest first");

    Response onlyLogins = feed(session, "type=login");
    assertTrue(types(onlyLogins).stream().allMatch("LOGIN"::equals));

    assertEquals(400, feed(session, "limit=abc").statusCode());
    assertEquals(400, feed(session, "limit=0").statusCode());
    assertEquals(400, feed(session, "offset=-1").statusCode());
    assertEquals(400, feed(session, "type=NOT_A_TYPE").statusCode());
  }

  @Test
  @DisplayName("the page size is capped by the tenant setting")
  void feed_pageSizeIsCappedByTheTenant() {
    Session session = newUser(false);
    for (int i = 0; i < 11; i++) {
      session = relogin(session, "password");
    }
    setActivityProperty("maxPageSize", "10");
    try {
      Response feed = feed(session, "limit=1000");
      assertEquals(200, feed.statusCode(), feed.asString());
      assertEquals(10, feed.<List<Object>>path("pageItems").size());
    } finally {
      setActivityProperty("maxPageSize", "100");
    }
  }

  @Test
  @DisplayName("a tenant that switches the log off gets nothing new")
  void disabled_recordsNothing() {
    Session session = newUser(false);
    awaitType(session, "LOGIN");
    long before = totalOf(session);

    setActivityProperty("enabled", "false");
    try {
      session = relogin(session, "password");
      session = relogin(session, "password");
      assertEquals(before, totalOf(session));
    } finally {
      setActivityProperty("enabled", "true");
    }
    // and it records again once switched back on
    session = relogin(session, "password");
    awaitType(session, "LOGIN");
    assertTrue(totalOf(session) > before);
  }

  @Test
  @DisplayName("without a login the feed is closed")
  void unauthenticated_isRejected() {
    Response response = given(SelfServiceTestUtils.requestSpec(getFineractPort())).get(ACTIVITY);
    assertTrue(response.statusCode() == 401 || response.statusCode() == 403, response.asString());
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static RequestSpecification admin() {
    return SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password");
  }

  private static RequestSpecification sessionSpec(Session session) {
    return SelfServiceTestUtils.requestSpec(getFineractPort())
        .header("Authorization", session.authHeader());
  }

  private static Response feed(Session session, String query) {
    return given(sessionSpec(session)).get(ACTIVITY + (query.isEmpty() ? "" : "?" + query));
  }

  private static long totalOf(Session session) {
    return feed(session, "limit=1").<Integer>path("totalFilteredRecords");
  }

  private static List<String> types(Response feed) {
    return feed.path("pageItems.type");
  }

  private static Map<String, Object> firstOfType(Response feed, String type) {
    List<Map<String, Object>> items = feed.path("pageItems");
    return items.stream()
        .filter(item -> type.equals(item.get("type")))
        .findFirst()
        .orElseThrow(() -> new AssertionError(type + " not in " + types(feed)));
  }

  /** Events are recorded when the request ends; allow a short moment anyway. */
  private static Response awaitType(Session session, String type) {
    Response last = null;
    for (int attempt = 0; attempt < 25; attempt++) {
      last = feed(session, "");
      if (last.statusCode() == 200 && types(last).contains(type)) {
        return last;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError(
        type + " never appeared; feed was " + (last == null ? "n/a" : last.asString()));
  }

  private static Session loginWith(String username, String password, Account account) {
    Response response =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .header("X-Forwarded-For", FORWARDED_IP)
            .body(Map.of("username", username, "password", password))
            .post(SelfServiceTestUtils.SELF_AUTH_PATH);
    assertEquals(200, response.statusCode(), response.asString());
    String key = response.path("base64EncodedAuthenticationKey");
    assertNotNull(key, "login must return a session key");
    long userId =
        Long.parseLong(
            querySingleValueInPostgres(
                "SELECT id FROM m_appselfservice_user WHERE username = " + sqlLiteral(username)));
    return new Session(username, userId, "Basic " + key, account);
  }

  private static Session relogin(Session session, String password) {
    return loginWith(session.username(), password, session.account());
  }

  private static void setActivityProperty(String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name ="
            + " 'SELF_SERVICE_ACTIVITY');",
        value, name);
  }

  private static void setStepUpProperty(String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name ="
            + " 'SELF_SERVICE_STEP_UP');",
        value, name);
  }

  private static void overwriteOtp(long challengeId, String otp) {
    String salt =
        querySingleValueInPostgres(
            "SELECT otp_salt FROM m_selfservice_stepup_challenge WHERE id = " + challengeId);
    executeSqlInPostgres(
        "UPDATE m_selfservice_stepup_challenge SET otp_hash = %s WHERE id = %s;",
        hmacHex(STEP_UP_HMAC_SECRET, "otp:" + salt + ":" + otp), challengeId);
  }

  private static String hmacHex(String secret, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Object> challengeBody(Session session, String amount) {
    Map<String, Object> body = new HashMap<>();
    body.put("action", "TPT_TRANSFER");
    body.put("channel", "EMAIL");
    body.put(
        "payload",
        Map.of(
            "fromAccountId",
            session.account().savingsId(),
            "fromAccountType",
            2,
            "toAccountId",
            receiver.savingsId(),
            "toAccountType",
            2,
            "transferAmount",
            amount));
    return body;
  }

  private static Response tpt(Session session, String amount, String stepUpToken) {
    Account sender = session.account();
    Map<String, Object> body = new HashMap<>();
    body.put("fromOfficeId", sender.officeId());
    body.put("fromClientId", sender.clientId());
    body.put("fromAccountId", sender.savingsId());
    body.put("fromAccountType", 2);
    body.put("toOfficeId", receiver.officeId());
    body.put("toClientId", receiver.clientId());
    body.put("toAccountId", receiver.savingsId());
    body.put("toAccountType", 2);
    body.put("transferAmount", amount);
    body.put("transferDate", today);
    body.put("transferDescription", "MX-491 activity integration test");
    body.put("dateFormat", DATE_FORMAT);
    body.put("locale", "en");
    RequestSpecification spec = sessionSpec(session);
    if (stepUpToken != null) {
      spec = spec.header("X-Step-Up-Token", stepUpToken);
    }
    return given(spec).body(body).post(TRANSFERS + "?type=tpt");
  }

  private static String receiverAccountNumber() {
    return given(admin())
        .get(CP + "/savingsaccounts/" + receiver.savingsId())
        .then()
        .statusCode(200)
        .extract()
        .path("accountNo");
  }

  private static long addBeneficiary(Session session) {
    Map<String, Object> beneficiary = new HashMap<>();
    beneficiary.put("locale", "en");
    beneficiary.put("name", "Receiver-" + receiver.clientId());
    beneficiary.put("officeName", "Head Office");
    beneficiary.put("accountNumber", receiverAccountNumber());
    beneficiary.put("accountType", 2);
    beneficiary.put("transferLimit", 1000000);
    beneficiary.put("paymentType", "SAME_BANK");
    beneficiary.put("currency", "USD");
    return given(sessionSpec(session))
        .body(beneficiary)
        .post(BENEFICIARIES)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getLong("resourceId");
  }

  /** A new self-service user with an own client and savings account, logged in. */
  private static Session newUser(boolean withBeneficiaryAndFunds) {
    Account account = seedClient("sender", withBeneficiaryAndFunds);
    String username = insertSelfServiceUser(account.clientId(), account.officeId());
    Session session = loginWith(username, "password", account);
    if (withBeneficiaryAndFunds) {
      addBeneficiary(session);
    }
    return session;
  }

  private static void disableDailyTptLimit() {
    List<Map<String, Object>> configs =
        given(admin())
            .get(CP + "/configurations")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("globalConfiguration");
    for (Map<String, Object> cfg : configs) {
      if ("daily-tpt-limit".equals(cfg.get("name"))) {
        given(admin())
            .body(Map.of("enabled", false))
            .put(CP + "/configurations/" + cfg.get("id"))
            .then()
            .statusCode(200);
      }
    }
  }

  private static Account seedClient(String label, boolean funded) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> client = new HashMap<>();
    client.put("officeId", 1);
    client.put("legalFormId", 1);
    client.put("firstname", label);
    client.put("lastname", suffix);
    client.put("externalId", label + "-" + suffix);
    client.put("dateFormat", DATE_FORMAT);
    client.put("locale", "en_GB");
    client.put("active", true);
    client.put("activationDate", today);
    Integer clientId =
        given(admin())
            .body(client)
            .post(CP + "/clients")
            .then()
            .statusCode(200)
            .extract()
            .path("clientId");
    Integer officeId =
        given(admin())
            .get(CP + "/clients/" + clientId)
            .then()
            .statusCode(200)
            .extract()
            .path("officeId");

    Map<String, Object> product = new HashMap<>();
    product.put("name", "Activity-Product-" + suffix);
    product.put("shortName", suffix.substring(0, 4));
    product.put("description", "MX-491 zero-fee savings product");
    product.put("currencyCode", "USD");
    product.put("digitsAfterDecimal", "4");
    product.put("inMultiplesOf", "0");
    product.put("locale", "en_GB");
    product.put("nominalAnnualInterestRate", "0");
    product.put("interestCalculationType", "1");
    product.put("interestCalculationDaysInYearType", "365");
    product.put("interestCompoundingPeriodType", "4");
    product.put("interestPostingPeriodType", "4");
    product.put("accountingRule", "1");
    product.put("charges", List.of());
    Integer productId =
        given(admin())
            .body(product)
            .post(CP + "/savingsproducts")
            .then()
            .statusCode(200)
            .extract()
            .path("resourceId");

    Map<String, Object> account = new HashMap<>();
    account.put("clientId", clientId);
    account.put("productId", productId);
    account.put("locale", "en_GB");
    account.put("dateFormat", DATE_FORMAT);
    account.put("submittedOnDate", today);
    Integer savingsId =
        given(admin())
            .body(account)
            .post(CP + "/savingsaccounts")
            .then()
            .statusCode(200)
            .extract()
            .path("savingsId");
    given(admin())
        .body(Map.of("locale", "en", "dateFormat", DATE_FORMAT, "approvedOnDate", today))
        .post(CP + "/savingsaccounts/" + savingsId + "?command=approve")
        .then()
        .statusCode(200);
    given(admin())
        .body(Map.of("locale", "en", "dateFormat", DATE_FORMAT, "activatedOnDate", today))
        .post(CP + "/savingsaccounts/" + savingsId + "?command=activate")
        .then()
        .statusCode(200);
    if (funded) {
      Map<String, Object> deposit = new HashMap<>();
      deposit.put("locale", "en");
      deposit.put("dateFormat", DATE_FORMAT);
      deposit.put("transactionDate", today);
      deposit.put("transactionAmount", new BigDecimal("100000.00"));
      deposit.put("paymentTypeId", 1);
      given(admin())
          .body(deposit)
          .post(CP + "/savingsaccounts/" + savingsId + "/transactions?command=deposit")
          .then()
          .statusCode(200);
    }
    return new Account(clientId, officeId, savingsId);
  }

  /** A self-service user whose password is "password" (the hash of the demo admin user). */
  private static String insertSelfServiceUser(Integer clientId, Integer officeId) {
    String username = "ssact_" + UUID.randomUUID().toString().substring(0, 8);
    executeSqlInPostgres(
        """
        CREATE TEMP TABLE new_id ON COMMIT DROP AS
        SELECT GREATEST(
            COALESCE((SELECT MAX(id) FROM m_appuser), 0),
            COALESCE((SELECT MAX(id) FROM m_appselfservice_user), 0)
        ) + 1 AS id;

        INSERT INTO m_appselfservice_user(
            id, office_id, username, password, email, firstname, lastname, is_deleted,
            nonexpired, nonlocked, nonexpired_credentials, enabled, firsttime_login_remaining,
            password_never_expires, is_self_service_user, password_reset_required
        )
        SELECT id, %1$s, %2$s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1),
            %3$s, 'SS', 'ACTIVITY', false, true, true, true, true, false, true, true, false
        FROM new_id;

        INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
        SELECT id, %4$s FROM new_id;

        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %5$s FROM new_id;

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
                sqlLiteral(officeId),
                sqlLiteral(username),
                sqlLiteral(username + "@fineract.org"),
                sqlLiteral(roleId),
                sqlLiteral(clientId)));
    return username;
  }
}
