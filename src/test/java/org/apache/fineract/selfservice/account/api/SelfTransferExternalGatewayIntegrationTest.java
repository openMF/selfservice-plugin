/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * PIN and SINPE confirms against external gateways that accepted the transfer (MX-503), on a real
 * Fineract + PostgreSQL started by Testcontainers with the plugin on the classpath.
 *
 * <p>Both gateways are one WireMock container on the Fineract network, so the test counts the real
 * HTTP calls the plugin makes. Once a gateway accepted a transfer the money has left: a later
 * failure must not roll back the consumed step-up token, or the same token would send it again.
 *
 * <p>Step-up OTPs are made known the same way as in {@code SelfStepUpIntegrationTest}.
 */
class SelfTransferExternalGatewayIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH);
  private static final String CP = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String STEPUP = CP + "/self/stepup";
  private static final String CONFIRM = CP + "/self/accounttransfers/confirm";
  private static final String KNOWN_OTP = "123456";
  private static final String GATEWAY_ALIAS = "transfer-gateways";
  private static final String PIN_TRANSFER_PATH = "/pin/api/v1/transfers/transfer";
  private static final String SINPE_TRANSFER_PATH = "/sinpe/transfer/account-to-phone";

  private static final GenericContainer<?> gateways =
      new GenericContainer<>("wiremock/wiremock:3.9.1")
          .withNetwork(fineract.getNetwork())
          .withNetworkAliases(GATEWAY_ALIAS)
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

  private static String today;
  private static Integer roleId;
  private static Map<String, Map<String, String>> originalGatewaySettings;
  private static String originalProtectedActions;

  private record SeedResult(Integer clientId, Integer officeId, Integer savingsId) {}

  /** A logged-in self-service user: session header value and the client it owns. */
  private record Session(String authHeader, SeedResult account) {}

  @BeforeAll
  static void seed() {
    gateways.start();
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

    originalProtectedActions = serviceProperty("SELF_SERVICE_STEP_UP", "protectedActions");
    originalGatewaySettings = new HashMap<>();
    for (String service : List.of("PinService", "SinpeService")) {
      originalGatewaySettings.put(
          service,
          Map.of(
              "host", serviceProperty(service, "host"),
              "isEnabled", serviceProperty(service, "isEnabled")));
    }
    setServiceProperty("PinService", "host", "http://" + GATEWAY_ALIAS + ":8080/pin");
    setServiceProperty("SinpeService", "host", "http://" + GATEWAY_ALIAS + ":8080/sinpe");
    setServiceProperty("PinService", "isEnabled", "true");
    setServiceProperty("SinpeService", "isEnabled", "true");
  }

  /**
   * All integration test classes share one Fineract container and one tenant database, so this
   * class leaves step-up and both gateways as it found them.
   */
  @AfterAll
  static void restoreSharedSettings() {
    setServiceProperty("SELF_SERVICE_STEP_UP", "enabled", "false");
    setServiceProperty("SELF_SERVICE_STEP_UP", "challengeRateLimit", "5");
    setServiceProperty("SELF_SERVICE_STEP_UP", "hmacSecret", "change-me-before-enabling");
    setServiceProperty("SELF_SERVICE_STEP_UP", "protectedActions", originalProtectedActions);
    originalGatewaySettings.forEach(
        (service, settings) ->
            settings.forEach((name, value) -> setServiceProperty(service, name, value)));
    gateways.stop();
  }

  @BeforeEach
  void stepUpOnAndGatewaysReset() {
    setServiceProperty("SELF_SERVICE_STEP_UP", "hmacSecret", STEP_UP_HMAC_SECRET);
    setServiceProperty("SELF_SERVICE_STEP_UP", "enabled", "true");
    setServiceProperty("SELF_SERVICE_STEP_UP", "challengeRateLimit", "50");
    setServiceProperty("SELF_SERVICE_STEP_UP", "protectedActions", "CONFIRM_TRANSFER");
    gatewayAdmin().post("/__admin/reset").then().statusCode(200);
    stubGateway("GET", "/pin/api/v1/accounts/.*/info", destinationInfo());
  }

  // ---------------------------------------------------------------------------------------------
  // accepted by the gateway: the token is spent, a retry never reaches the gateway
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PIN accepted with an answer that is not JSON: confirmed once, never sent twice")
  void pin_acceptedWithNonJsonAnswer_isSentOnce() {
    stubGateway("POST", PIN_TRANSFER_PATH, "OK");
    Session session = newUser();
    Map<String, Object> body = pinConfirm(session, "40.00");
    String token = confirmToken(session, body);

    Response confirmed = confirm(session, token, body);
    assertEquals(200, confirmed.statusCode(), confirmed.asString());
    assertEquals("PIN", confirmed.path("transferType"));
    assertEquals(1, gatewayCalls(PIN_TRANSFER_PATH));

    Response retry = confirm(session, token, body);
    assertEquals(403, retry.statusCode(), retry.asString());
    assertEquals(
        "error.msg.selfservice.stepup.invalid", retry.path("userMessageGlobalisationCode"));
    assertEquals(1, gatewayCalls(PIN_TRANSFER_PATH), "the same token must not send it again");
  }

  @Test
  @DisplayName("SINPE accepted with an answer that is not JSON: confirmed once, never sent twice")
  void sinpe_acceptedWithNonJsonAnswer_isSentOnce() {
    stubGateway("POST", SINPE_TRANSFER_PATH, "<html>accepted</html>");
    Session session = newUser();
    Map<String, Object> body = sinpeConfirm(session, "40.00");
    String token = confirmToken(session, body);

    Response confirmed = confirm(session, token, body);
    assertEquals(200, confirmed.statusCode(), confirmed.asString());
    assertEquals("SINPE_MOVIL", confirmed.path("transferType"));
    assertEquals(1, gatewayCalls(SINPE_TRANSFER_PATH));

    Response retry = confirm(session, token, body);
    assertEquals(403, retry.statusCode(), retry.asString());
    assertEquals(
        "error.msg.selfservice.stepup.invalid", retry.path("userMessageGlobalisationCode"));
    assertEquals(1, gatewayCalls(SINPE_TRANSFER_PATH), "the same token must not send it again");
  }

  @Test
  @DisplayName("PIN accepted with a JSON answer: confirmed once, never sent twice")
  void pin_acceptedWithJsonAnswer_isSentOnce() {
    stubGateway(
        "POST", PIN_TRANSFER_PATH, "{\"operationId\":\"PIN-OP-1\",\"internalRefNumber\":\"R-1\"}");
    Session session = newUser();
    Map<String, Object> body = pinConfirm(session, "40.00");
    String token = confirmToken(session, body);

    Response confirmed = confirm(session, token, body);
    assertEquals(200, confirmed.statusCode(), confirmed.asString());
    assertEquals("PIN-OP-1", confirmed.path("data.operationId"));

    assertEquals(403, confirm(session, token, body).statusCode());
    assertEquals(1, gatewayCalls(PIN_TRANSFER_PATH));
  }

  // ---------------------------------------------------------------------------------------------
  // rejected by the gateway: nothing left, so the same token may try again
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PIN rejected by the gateway: the confirm fails and the token is not spent")
  void pin_rejectedByTheGateway_keepsTheToken() {
    stubGateway("POST", PIN_TRANSFER_PATH, "{\"error\":\"insufficient funds at the gateway\"}");
    Session session = newUser();
    Map<String, Object> body = pinConfirm(session, "40.00");
    String token = confirmToken(session, body);

    Response rejected = confirm(session, token, body);
    assertTrue(rejected.statusCode() >= 400, rejected.asString());

    Response retry = confirm(session, token, body);
    assertTrue(retry.statusCode() >= 400, retry.asString());
    assertEquals(2, gatewayCalls(PIN_TRANSFER_PATH), "a rejected transfer may be tried again");
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: confirms
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> pinConfirm(Session session, String amount) {
    Map<String, Object> body = new HashMap<>();
    body.put("fromAccount", String.valueOf(session.account().savingsId()));
    body.put("fromAccountType", 2);
    body.put("toAccount", "CR05015202001026284066");
    body.put("transferType", "PIN");
    body.put("currencyCode", "USD");
    body.put("transferAmount", new BigDecimal(amount));
    body.put("transferDescription", "MX-503 gateway integration test");
    return body;
  }

  private static Map<String, Object> sinpeConfirm(Session session, String amount) {
    Map<String, Object> body = pinConfirm(session, amount);
    body.put("toAccount", "88887777");
    body.put("transferType", "SINPE_MOVIL");
    body.put("currencyCode", "CRC");
    return body;
  }

  private static Response confirm(Session session, String token, Map<String, Object> body) {
    return given(sessionSpec(session).header("X-Step-Up-Token", token)).body(body).post(CONFIRM);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: step-up
  // ---------------------------------------------------------------------------------------------

  /** A verified CONFIRM_TRANSFER token bound to exactly this confirm body. */
  private static String confirmToken(Session session, Map<String, Object> confirm) {
    Map<String, Object> payload = new HashMap<>();
    for (String key :
        List.of("fromAccount", "fromAccountType", "toAccount", "transferType", "currencyCode")) {
      payload.put(key, confirm.get(key));
    }
    payload.put("transferAmount", confirm.get("transferAmount"));
    Response challenge =
        given(sessionSpec(session))
            .body(Map.of("action", "CONFIRM_TRANSFER", "channel", "EMAIL", "payload", payload))
            .post(STEPUP + "/challenges");
    assertEquals(201, challenge.statusCode(), challenge.asString());
    long challengeId = challenge.jsonPath().getLong("challengeId");
    String salt =
        querySingleValueInPostgres(
            "SELECT otp_salt FROM m_selfservice_stepup_challenge WHERE id = " + challengeId);
    executeSqlInPostgres(
        "UPDATE m_selfservice_stepup_challenge SET otp_hash = %s WHERE id = %s;",
        hmacHex(STEP_UP_HMAC_SECRET, "otp:" + salt + ":" + KNOWN_OTP), challengeId);
    return given(sessionSpec(session))
        .body(Map.of("otp", KNOWN_OTP))
        .post(STEPUP + "/challenges/" + challengeId + "/verify")
        .then()
        .statusCode(200)
        .extract()
        .path("stepUpToken");
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

  // ---------------------------------------------------------------------------------------------
  // helpers: settings and gateways (WireMock)
  // ---------------------------------------------------------------------------------------------

  private static String serviceProperty(String service, String name) {
    return querySingleValueInPostgres(
        "SELECT p.value FROM c_external_service_properties p JOIN c_external_service s ON"
            + " p.external_service_id = s.id WHERE s.name = '"
            + service
            + "' AND p.name = '"
            + name
            + "'");
  }

  private static void setServiceProperty(String service, String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name = %s);",
        value, name, service);
  }

  private static RequestSpecification gatewayAdmin() {
    return given()
        .baseUri("http://" + gateways.getHost())
        .port(gateways.getMappedPort(8080))
        .contentType("application/json");
  }

  private static void stubGateway(String method, String urlPathPattern, String body) {
    gatewayAdmin()
        .body(
            Map.of(
                "request",
                Map.of("method", method, "urlPathPattern", urlPathPattern),
                "response",
                Map.of("status", 200, "body", body)))
        .post("/__admin/mappings")
        .then()
        .statusCode(201);
  }

  private static String destinationInfo() {
    return "{\"holder\":\"PIN Receiver SA\",\"holderId\":\"3101123456\",\"holderIdType\":\"2\","
        + "\"currencyCode\":\"USD\"}";
  }

  private static int gatewayCalls(String path) {
    return gatewayAdmin()
        .body(Map.of("method", "POST", "urlPath", path))
        .post("/__admin/requests/count")
        .then()
        .statusCode(200)
        .extract()
        .path("count");
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: seeding
  // ---------------------------------------------------------------------------------------------

  private static RequestSpecification admin() {
    return SelfServiceTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password");
  }

  private static RequestSpecification sessionSpec(Session session) {
    return SelfServiceTestUtils.requestSpec(getFineractPort())
        .header("Authorization", session.authHeader());
  }

  /** A new self-service user who owns a funded client of their own, logged in. */
  private static Session newUser() {
    SeedResult account = seedFundedClient();
    String username = insertSelfServiceUser(account.clientId(), account.officeId());
    Response response = SelfServiceTestUtils.authenticate(getFineractPort(), username, "password");
    assertEquals(200, response.statusCode(), response.asString());
    String key = response.path("base64EncodedAuthenticationKey");
    assertNotNull(key, "login must return a session key");
    return new Session("Basic " + key, account);
  }

  private static SeedResult seedFundedClient() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> client = new HashMap<>();
    client.put("officeId", 1);
    client.put("legalFormId", 1);
    client.put("firstname", "gateway");
    client.put("lastname", suffix);
    client.put("externalId", "gateway-" + suffix);
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
    product.put("name", "Gateway-Product-" + suffix);
    product.put("shortName", suffix.substring(0, 4));
    product.put("description", "MX-503 zero-fee savings product");
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
    return new SeedResult(clientId, officeId, savingsId);
  }

  private static String insertSelfServiceUser(Integer clientId, Integer officeId) {
    String username = "ssgw_" + UUID.randomUUID().toString().substring(0, 8);
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
        'SS', 'GATEWAY', false, true, true, true, true, false
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
        'SS', 'GATEWAY', false, true, true, true, true, false, true, true, false
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
