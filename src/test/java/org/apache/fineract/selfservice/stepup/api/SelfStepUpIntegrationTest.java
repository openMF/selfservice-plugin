/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end step-up (MX-488) against a real Fineract + PostgreSQL started by Testcontainers with
 * the plugin on the classpath.
 *
 * <p>The OTP is delivered by e-mail/SMS and therefore not readable from the test. Challenges store
 * only a salted HMAC, so the test replaces the stored hash with the HMAC of a known code, computed
 * here independently with the same secret the container is started with. That exercises the real
 * verify endpoint, row locking, attempt counting, grant issue and one-time consumption.
 */
class SelfStepUpIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH);
  private static final String CP = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String STEPUP = CP + "/self/stepup";
  private static final String TRANSFERS = CP + "/self/accounttransfers";
  private static final String KNOWN_OTP = "123456";

  private static String today;
  private static SeedResult receiver;
  private static Integer roleId;

  private record SeedResult(Integer clientId, Integer officeId, Integer savingsId) {}

  /** A logged-in self-service user: session header value and the user name. */
  private record Session(String username, String authHeader, SeedResult account) {}

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
  }

  @BeforeEach
  void stepUpOnWithGenerousRateLimit() {
    setStepUpProperty("enabled", "true");
    setStepUpProperty("challengeRateLimit", "50");
  }

  // ---------------------------------------------------------------------------------------------
  // default: disabled
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("step-up disabled (default): config says so, no challenge, transfers unchanged")
  void disabledByDefault_behaviourIsUnchanged() {
    setStepUpProperty("enabled", "false");
    Session session = newUserWithBeneficiary();

    given(sessionSpec(session))
        .get(STEPUP + "/config")
        .then()
        .statusCode(200)
        .body("enabled", org.hamcrest.Matchers.equalTo(false))
        .body("sessionBound", org.hamcrest.Matchers.equalTo(true));

    given(sessionSpec(session))
        .body(challengeBody(session, "TPT_TRANSFER", "EMAIL", "5"))
        .post(STEPUP + "/challenges")
        .then()
        .statusCode(400)
        .body(
            "userMessageGlobalisationCode",
            org.hamcrest.Matchers.equalTo("error.msg.selfservice.stepup.disabled"));

    assertEquals(200, tpt(session, "1.00").statusCode());
  }

  // ---------------------------------------------------------------------------------------------
  // enforcement
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("enabled: a TPT without a step-up token is rejected with a machine readable error")
  void enabled_tptWithoutToken_isRejected_andNoMoneyMoves() {
    Session session = newUserWithBeneficiary();
    BigDecimal before = balance(session.account());

    Response response = tpt(session, "7.00");

    assertEquals(403, response.statusCode(), response.asString());
    assertEquals(
        "error.msg.selfservice.stepup.required", response.path("userMessageGlobalisationCode"));
    assertEquals("TPT_TRANSFER", response.path("action"));
    assertEquals(0, before.compareTo(balance(session.account())));
  }

  @Test
  @DisplayName("enabled: credential based Basic auth has no session, so step-up cannot be used")
  void enabled_credentialBasedAuth_hasNoSession() {
    Session session = newUserWithBeneficiary();
    RequestSpecification credentialSpec =
        SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), session.username(), "password");

    Response response = tpt(credentialSpec, session, "7.00");

    assertEquals(403, response.statusCode(), response.asString());
    assertEquals(
        "error.msg.selfservice.stepup.session.required",
        response.path("userMessageGlobalisationCode"));
    given(credentialSpec)
        .body(challengeBody(session, "TPT_TRANSFER", "EMAIL", "5"))
        .post(STEPUP + "/challenges")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("enabled: challenge -> verify -> transfer works once and the token is single use")
  void enabled_fullTptFlow_tokenIsSingleUse() {
    Session session = newUserWithBeneficiary();
    BigDecimal before = balance(session.account());

    Response challenge =
        given(sessionSpec(session))
            .body(challengeBody(session, "TPT_TRANSFER", "EMAIL", "25.00"))
            .post(STEPUP + "/challenges")
            .then()
            .statusCode(201)
            .extract()
            .response();
    long challengeId = challenge.jsonPath().getLong("challengeId");
    assertEquals("EMAIL", challenge.path("channel"));
    assertEquals(5, (int) challenge.path("attemptsRemaining"));
    assertNotNull(challenge.path("maskedDestination"));
    assertFalse(challenge.asString().contains(KNOWN_OTP));
    assertFalse(challenge.asString().toLowerCase(Locale.ROOT).contains("authcode"));

    overwriteOtp(challengeId, KNOWN_OTP);

    given(sessionSpec(session))
        .body(Map.of("otp", "000000"))
        .post(STEPUP + "/challenges/" + challengeId + "/verify")
        .then()
        .statusCode(400)
        .body(
            "userMessageGlobalisationCode",
            org.hamcrest.Matchers.equalTo("error.msg.selfservice.stepup.otp.invalid"))
        .body("attemptsRemaining", org.hamcrest.Matchers.equalTo(4));

    Response verified =
        given(sessionSpec(session))
            .body(Map.of("otp", KNOWN_OTP))
            .post(STEPUP + "/challenges/" + challengeId + "/verify")
            .then()
            .statusCode(200)
            .extract()
            .response();
    String token = verified.path("stepUpToken");
    assertNotNull(token);
    assertEquals("TPT_TRANSFER", verified.path("action"));

    // The grant is stored only as a digest.
    String storedGrant =
        querySingleValueInPostgres(
            "SELECT grant_hash FROM m_selfservice_stepup_challenge WHERE id = " + challengeId);
    assertFalse(storedGrant.contains(token));
    assertEquals(64, storedGrant.length());

    assertEquals(
        200,
        tpt(withToken(session, token), session, "25.00").statusCode(),
        "transfer with a valid token must succeed");
    assertEquals(0, before.subtract(new BigDecimal("25.00")).compareTo(balance(session.account())));

    Response replay = tpt(withToken(session, token), session, "25.00");
    assertEquals(403, replay.statusCode(), "the same token must not work twice");
    assertEquals(
        "error.msg.selfservice.stepup.invalid", replay.path("userMessageGlobalisationCode"));
    assertEquals(0, before.subtract(new BigDecimal("25.00")).compareTo(balance(session.account())));
  }

  @Test
  @DisplayName("enabled: a token approved for one amount cannot execute another amount")
  void enabled_tokenIsBoundToThePayload() {
    Session session = newUserWithBeneficiary();
    String token = approvedToken(session, "TPT_TRANSFER", "5.00");
    BigDecimal before = balance(session.account());

    Response tampered = tpt(withToken(session, token), session, "6.00");
    assertEquals(403, tampered.statusCode());
    assertEquals(
        "error.msg.selfservice.stepup.invalid", tampered.path("userMessageGlobalisationCode"));
    assertEquals(0, before.compareTo(balance(session.account())));

    // The rejected attempt did not burn the token: the approved transfer still works.
    assertEquals(200, tpt(withToken(session, token), session, "5.00").statusCode());
    assertEquals(0, before.subtract(new BigDecimal("5.00")).compareTo(balance(session.account())));
  }

  @Test
  @DisplayName("enabled: a token from one action cannot be used for another action")
  void enabled_tokenIsBoundToTheAction() {
    Session session = newUserWithBeneficiary();
    String passwordToken = approvedToken(session, "CHANGE_PASSWORD", null);

    Response response = tpt(withToken(session, passwordToken), session, "5.00");

    assertEquals(403, response.statusCode());
    assertEquals(
        "error.msg.selfservice.stepup.invalid", response.path("userMessageGlobalisationCode"));
  }

  // ---------------------------------------------------------------------------------------------
  // ownership / session
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a challenge cannot be verified or resent by another user")
  void challengeIsPrivateToItsOwner() {
    Session owner = newUserWithBeneficiary();
    Session other = newUserWithBeneficiary();
    long challengeId = createChallenge(owner, "TPT_TRANSFER", "5.00");
    overwriteOtp(challengeId, KNOWN_OTP);

    given(sessionSpec(other))
        .body(Map.of("otp", KNOWN_OTP))
        .post(STEPUP + "/challenges/" + challengeId + "/verify")
        .then()
        .statusCode(403)
        .body(
            "userMessageGlobalisationCode",
            org.hamcrest.Matchers.equalTo("error.msg.selfservice.stepup.invalid"));
    given(sessionSpec(other))
        .post(STEPUP + "/challenges/" + challengeId + "/resend")
        .then()
        .statusCode(403);
  }

  @Test
  @DisplayName("a challenge dies with the session: a new login cannot continue it")
  void challengeIsBoundToTheSession() {
    Session first = newUserWithBeneficiary();
    long challengeId = createChallenge(first, "TPT_TRANSFER", "5.00");
    overwriteOtp(challengeId, KNOWN_OTP);

    Session second = login(first.username(), first.account());

    given(sessionSpec(second))
        .body(Map.of("otp", KNOWN_OTP))
        .post(STEPUP + "/challenges/" + challengeId + "/verify")
        .then()
        .statusCode(403);
  }

  // ---------------------------------------------------------------------------------------------
  // brute force / resend
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("five wrong codes lock the challenge, even the right code and new challenges fail")
  void lockoutAfterMaxAttempts() {
    Session session = newUserWithBeneficiary();
    long challengeId = createChallenge(session, "TPT_TRANSFER", "5.00");
    overwriteOtp(challengeId, KNOWN_OTP);

    for (int attempt = 1; attempt <= 4; attempt++) {
      given(sessionSpec(session))
          .body(Map.of("otp", "00000" + attempt))
          .post(STEPUP + "/challenges/" + challengeId + "/verify")
          .then()
          .statusCode(400);
    }
    Response locked =
        given(sessionSpec(session))
            .body(Map.of("otp", "000009"))
            .post(STEPUP + "/challenges/" + challengeId + "/verify");
    assertEquals(429, locked.statusCode(), locked.asString());
    assertEquals(
        "error.msg.selfservice.stepup.locked", locked.path("userMessageGlobalisationCode"));
    assertNotNull(locked.getHeader("Retry-After"));

    assertEquals(
        429,
        given(sessionSpec(session))
            .body(Map.of("otp", KNOWN_OTP))
            .post(STEPUP + "/challenges/" + challengeId + "/verify")
            .statusCode());
    assertEquals(
        429,
        given(sessionSpec(session))
            .body(challengeBody(session, "TPT_TRANSFER", "EMAIL", "5.00"))
            .post(STEPUP + "/challenges")
            .statusCode());
  }

  @Test
  @DisplayName("resend honours the cooldown, then issues a new code")
  void resendCooldown() {
    Session session = newUserWithBeneficiary();
    long challengeId = createChallenge(session, "TPT_TRANSFER", "5.00");

    Response tooSoon =
        given(sessionSpec(session)).post(STEPUP + "/challenges/" + challengeId + "/resend");
    assertEquals(429, tooSoon.statusCode(), tooSoon.asString());
    assertEquals(
        "error.msg.selfservice.stepup.resend.cooldown",
        tooSoon.path("userMessageGlobalisationCode"));
    assertNotNull(tooSoon.getHeader("Retry-After"));

    executeSqlInPostgres(
        "UPDATE m_selfservice_stepup_challenge SET last_sent_at = last_sent_at - INTERVAL '120"
            + " seconds' WHERE id = %s;",
        challengeId);

    Response resent =
        given(sessionSpec(session)).post(STEPUP + "/challenges/" + challengeId + "/resend");
    assertEquals(200, resent.statusCode(), resent.asString());
    assertEquals(2, (int) resent.path("resendsRemaining"));
    assertFalse(resent.asString().contains(KNOWN_OTP));
  }

  // ---------------------------------------------------------------------------------------------
  // password change
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("password change needs step-up and then forces a new login")
  void passwordChange_needsStepUp_andRevokesTheSession() {
    Session session = newUserWithBeneficiary();
    Map<String, Object> change =
        Map.of("password", "N3w-Pa5w0rd-2026!", "repeatPassword", "N3w-Pa5w0rd-2026!");

    Response withoutToken = given(sessionSpec(session)).body(change).put(CP + "/self/user");
    assertEquals(403, withoutToken.statusCode(), withoutToken.asString());
    assertEquals(
        "error.msg.selfservice.stepup.required", withoutToken.path("userMessageGlobalisationCode"));
    assertEquals("CHANGE_PASSWORD", withoutToken.path("action"));

    String token = approvedToken(session, "CHANGE_PASSWORD", null);
    Response changed = given(withToken(session, token)).body(change).put(CP + "/self/user");
    assertEquals(200, changed.statusCode(), changed.asString());

    // US-E01-03: the old session is gone, the user must log in again.
    int afterChange = given(sessionSpec(session)).get(CP + "/self/clients").statusCode();
    assertTrue(afterChange == 401 || afterChange == 403, "old session still valid: " + afterChange);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: step-up
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> challengeBody(
      Session session, String action, String channel, String amount) {
    Map<String, Object> body = new HashMap<>();
    body.put("action", action);
    body.put("channel", channel);
    if (amount != null) {
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
    }
    return body;
  }

  private long createChallenge(Session session, String action, String amount) {
    return given(sessionSpec(session))
        .body(challengeBody(session, action, "EMAIL", amount))
        .post(STEPUP + "/challenges")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("challengeId");
  }

  /** Creates a challenge, forces a known OTP, verifies it and returns the one-time token. */
  private String approvedToken(Session session, String action, String amount) {
    long challengeId = createChallenge(session, action, amount);
    overwriteOtp(challengeId, KNOWN_OTP);
    return given(sessionSpec(session))
        .body(Map.of("otp", KNOWN_OTP))
        .post(STEPUP + "/challenges/" + challengeId + "/verify")
        .then()
        .statusCode(200)
        .extract()
        .path("stepUpToken");
  }

  /** Replaces the stored OTP hash with the HMAC of {@code otp}, computed independently here. */
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

  private static void setStepUpProperty(String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name ="
            + " 'SELF_SERVICE_STEP_UP');",
        value, name);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: requests
  // ---------------------------------------------------------------------------------------------

  private static RequestSpecification admin() {
    return SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password");
  }

  private static RequestSpecification sessionSpec(Session session) {
    return SelfServiceTestUtils.requestSpec(getFineractPort())
        .header("Authorization", session.authHeader());
  }

  private static RequestSpecification withToken(Session session, String token) {
    return sessionSpec(session).header("X-Step-Up-Token", token);
  }

  /** Logs in with {@code password} and returns the session-token based Authorization value. */
  private static Session login(String username, SeedResult account) {
    Response response = SelfServiceTestUtils.authenticate(getFineractPort(), username, "password");
    assertEquals(200, response.statusCode(), response.asString());
    String key = response.path("base64EncodedAuthenticationKey");
    assertNotNull(key, "login must return a session key");
    return new Session(username, "Basic " + key, account);
  }

  private static Response tpt(Session session, String amount) {
    return tpt(sessionSpec(session), session, amount);
  }

  private static Response tpt(RequestSpecification spec, Session session, String amount) {
    SeedResult sender = session.account();
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
    body.put("transferDescription", "MX-488 step-up integration test");
    body.put("dateFormat", DATE_FORMAT);
    body.put("locale", "en");
    return given(spec).body(body).post(TRANSFERS + "?type=tpt");
  }

  private static BigDecimal balance(SeedResult account) {
    Float value =
        given(admin())
            .get(CP + "/savingsaccounts/" + account.savingsId())
            .then()
            .statusCode(200)
            .extract()
            .path("summary.accountBalance");
    return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: seeding
  // ---------------------------------------------------------------------------------------------

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

  private static SeedResult seedClient(String label, boolean funded) {
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
    product.put("name", "StepUp-Product-" + suffix);
    product.put("shortName", suffix.substring(0, 4));
    product.put("description", "MX-488 zero-fee savings product");
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
    return new SeedResult(clientId, officeId, savingsId);
  }

  /**
   * A new self-service user mapped to its own funded client, logged in, with the receiver as TPT.
   */
  private Session newUserWithBeneficiary() {
    SeedResult account = seedClient("sender", true);
    String username = insertSelfServiceUser(account.clientId(), account.officeId());
    Session session = login(username, account);

    String receiverAccountNumber =
        given(admin())
            .get(CP + "/savingsaccounts/" + receiver.savingsId())
            .then()
            .statusCode(200)
            .extract()
            .path("accountNo");
    Map<String, Object> beneficiary = new HashMap<>();
    beneficiary.put("locale", "en");
    beneficiary.put("name", "Receiver-" + receiver.clientId());
    beneficiary.put("officeName", "Head Office");
    beneficiary.put("accountNumber", receiverAccountNumber);
    beneficiary.put("accountType", 2);
    beneficiary.put("transferLimit", 1000000);
    beneficiary.put("paymentType", "SAME_BANK");
    beneficiary.put("currency", "USD");
    given(sessionSpec(session))
        .body(beneficiary)
        .post(CP + "/self/beneficiaries/tpt")
        .then()
        .statusCode(200);
    return session;
  }

  private String insertSelfServiceUser(Integer clientId, Integer officeId) {
    String username = "ssup_" + UUID.randomUUID().toString().substring(0, 8);
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
        'SS', 'STEPUP', false, true, true, true, true, false
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
        'SS', 'STEPUP', false, true, true, true, true, false, true, true, false
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
}
