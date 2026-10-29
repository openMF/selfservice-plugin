/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
 * End-to-end dual approval (MX-489) against a real Fineract + PostgreSQL started by Testcontainers
 * with the plugin on the classpath.
 *
 * <p>An ENTITY client has a USD threshold and two signatories: the initiator (who owns the client)
 * and an approver. Transfers at or above the threshold are held by {@code /confirm} and executed
 * only when the approver approves with a step-up token bound to the approval.
 *
 * <p>SAME_BANK transfers move money inside Fineract, so balances and transfer rows show how often a
 * transfer ran. PIN transfers go to an external gateway; here that gateway is a WireMock container
 * on the Fineract network, so the test counts the real HTTP calls the plugin makes.
 *
 * <p>Step-up OTPs are made known the same way as in {@code SelfStepUpIntegrationTest}.
 */
class SelfTransferDualApprovalIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH);
  private static final String CP = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String STEPUP = CP + "/self/stepup";
  private static final String TRANSFERS = CP + "/self/accounttransfers";
  private static final String APPROVALS = TRANSFERS + "/approvals";
  private static final String KNOWN_OTP = "123456";
  private static final BigDecimal THRESHOLD = new BigDecimal("100.00");
  private static final String PIN_GATEWAY_ALIAS = "pin-gateway";
  private static final String PIN_TRANSFER_PATH = "/api/v1/transfers/transfer";
  private static final String PIN_DESTINATION_IBAN = "CR05015202001026284066";

  private static final GenericContainer<?> pinGateway =
      new GenericContainer<>("wiremock/wiremock:3.9.1")
          .withNetwork(fineract.getNetwork())
          .withNetworkAliases(PIN_GATEWAY_ALIAS)
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

  private static String today;
  private static Integer roleId;
  private static SeedResult receiver;
  private static String originalProtectedActions;
  private static Map<String, String> originalPinService;

  private record SeedResult(Integer clientId, Integer officeId, Integer savingsId) {}

  /** A logged-in self-service user: id, session header value and the client it owns. */
  private record Session(Integer userId, String authHeader, SeedResult account) {}

  /** An ENTITY client with an initiator and an approver signatory, and a USD threshold. */
  private record Company(SeedResult account, Session initiator, Session approver) {}

  @BeforeAll
  static void seed() {
    pinGateway.start();
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

    originalProtectedActions = stepUpProperty("protectedActions");
    originalPinService = Map.of("host", pinProperty("host"), "isEnabled", pinProperty("isEnabled"));
    setPinProperty("host", "http://" + PIN_GATEWAY_ALIAS + ":8080");
    setPinProperty("isEnabled", "true");

    receiver = seedClient("receiver", 1, false);
  }

  /**
   * All integration test classes share one Fineract container and one tenant database, so this
   * class leaves step-up and the PIN service as it found them.
   */
  @AfterAll
  static void restoreSharedSettings() {
    setStepUpProperty("enabled", "false");
    setStepUpProperty("challengeRateLimit", "5");
    setStepUpProperty("hmacSecret", "change-me-before-enabling");
    setStepUpProperty("protectedActions", originalProtectedActions);
    setDualApprovalEnabled("false");
    originalPinService.forEach(SelfTransferDualApprovalIntegrationTest::setPinProperty);
    pinGateway.stop();
  }

  @BeforeEach
  void stepUpWithApprovalProtected() {
    setStepUpProperty("hmacSecret", STEP_UP_HMAC_SECRET);
    setStepUpProperty("enabled", "true");
    setStepUpProperty("challengeRateLimit", "50");
    setStepUpProperty("protectedActions", "CONFIRM_TRANSFER,APPROVE_SECOND_SIGNATURE");
    setDualApprovalEnabled("true");
    resetPinGateway("{\"operationId\":\"PIN-OP-1\",\"internalRefNumber\":\"PIN-REF-1\"}");
  }

  // ---------------------------------------------------------------------------------------------
  // SAME_BANK: hold, approve once, never twice
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("at the threshold the transfer is held; one approval moves the money exactly once")
  void heldTransfer_isExecutedOnceByTheSecondSignatory() {
    Company company = newCompany();
    BigDecimal senderBefore = balance(company.account());
    BigDecimal receiverBefore = balance(receiver);

    Response held = confirmSameBank(company, "100.00");
    assertEquals(200, held.statusCode(), held.asString());
    assertEquals("PENDING_APPROVAL", held.path("status"));
    long approvalId = held.jsonPath().getLong("approvalId");
    assertEquals("PENDING", approvalStatus(approvalId));
    assertEquals(0, senderBefore.compareTo(balance(company.account())), "held: no money moves");
    assertEquals(0, transferCount(company.account()));

    Response pending =
        given(sessionSpec(company.approver()))
            .get(APPROVALS)
            .then()
            .statusCode(200)
            .extract()
            .response();
    assertEquals(List.of((int) approvalId), pending.path("approvalId"));
    assertEquals(List.of(true), pending.path("canDecide"));

    Response approved = approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertEquals(200, approved.statusCode(), approved.asString());
    assertEquals("APPROVED", approvalStatus(approvalId));
    assertEquals(
        String.valueOf(company.approver().userId()),
        querySingleValueInPostgres(
            "SELECT decided_by FROM m_selfservice_transfer_approval WHERE id = " + approvalId));
    assertMovedOnce(company, senderBefore, receiverBefore, "100.00");

    // A second approval with a new, valid token for the same approval is refused.
    Response again = approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertEquals(400, again.statusCode(), again.asString());
    assertEquals("validation.msg.transferapproval.approvalId.already.decided", errorCode(again));
    assertMovedOnce(company, senderBefore, receiverBefore, "100.00");
    given(sessionSpec(company.approver()))
        .get(APPROVALS)
        .then()
        .statusCode(200)
        .body("size()", org.hamcrest.Matchers.equalTo(0));
  }

  @Test
  @DisplayName("below the threshold the transfer runs at once, without an approval")
  void belowThreshold_executesImmediately() {
    Company company = newCompany();
    BigDecimal senderBefore = balance(company.account());
    BigDecimal receiverBefore = balance(receiver);

    Response response = confirmSameBank(company, "99.99");

    assertEquals(200, response.statusCode(), response.asString());
    assertNotEquals("PENDING_APPROVAL", response.path("status"));
    assertEquals("0", approvalCount(company.account()));
    assertMovedOnce(company, senderBefore, receiverBefore, "99.99");
  }

  @Test
  @DisplayName("tenant switch off: at the threshold the transfer runs at once, without an approval")
  void tenantSwitchOff_executesImmediately() {
    Company company = newCompany();
    BigDecimal senderBefore = balance(company.account());
    BigDecimal receiverBefore = balance(receiver);
    setDualApprovalEnabled("false");

    Response response = confirmSameBank(company, "100.00");

    assertEquals(200, response.statusCode(), response.asString());
    assertNotEquals("PENDING_APPROVAL", response.path("status"));
    assertEquals("0", approvalCount(company.account()));
    assertMovedOnce(company, senderBefore, receiverBefore, "100.00");
  }

  @Test
  @DisplayName("rejected: the initiator, a non-signatory, no token, a foreign or replayed token")
  void approval_isRefusedWithoutTheRightSignatoryAndToken() {
    Company company = newCompany();
    BigDecimal senderBefore = balance(company.account());
    long approvalId = holdSameBank(company, "150.00");
    long otherApprovalId = holdSameBank(company, "120.00");

    // The initiator is a signatory too, but cannot approve their own transfer.
    Response byInitiator =
        approve(company.initiator(), approvalId, approvalToken(company.initiator(), approvalId));
    assertEquals(400, byInitiator.statusCode(), byInitiator.asString());
    assertEquals(
        "validation.msg.transferapproval.approvalId.initiator.cannot.decide",
        errorCode(byInitiator));

    // A user who is no signatory of this client gets the same answer as for a missing approval.
    Session outsider = newPersonUser();
    Response byOutsider = approve(outsider, approvalId, approvalToken(outsider, approvalId));
    assertEquals(400, byOutsider.statusCode(), byOutsider.asString());
    assertEquals("validation.msg.transferapproval.approvalId.not.found", errorCode(byOutsider));

    Response withoutToken =
        given(sessionSpec(company.approver()))
            .post(APPROVALS + "/" + approvalId + "?command=approve");
    assertEquals(403, withoutToken.statusCode(), withoutToken.asString());
    assertEquals(
        "error.msg.selfservice.stepup.required", withoutToken.path("userMessageGlobalisationCode"));

    // A token verified for another approval of the same client cannot approve this one.
    String tokenForOther = approvalToken(company, otherApprovalId);
    Response foreign = approve(company.approver(), approvalId, tokenForOther);
    assertEquals(403, foreign.statusCode(), foreign.asString());
    assertEquals(
        "error.msg.selfservice.stepup.invalid", foreign.path("userMessageGlobalisationCode"));

    assertEquals("PENDING", approvalStatus(approvalId));
    assertEquals(0, senderBefore.compareTo(balance(company.account())));

    // The foreign attempt did not burn the token: it still approves its own transfer, once.
    // Replaying it is refused before the token is looked at, because the approval is decided.
    assertEquals(200, approve(company.approver(), otherApprovalId, tokenForOther).statusCode());
    Response replay = approve(company.approver(), otherApprovalId, tokenForOther);
    assertEquals(400, replay.statusCode(), replay.asString());
    assertEquals("validation.msg.transferapproval.approvalId.already.decided", errorCode(replay));
    assertEquals(1, transferCount(company.account()));
  }

  @Test
  @DisplayName("reject closes the approval without moving money; it cannot be approved afterwards")
  void rejectedTransfer_neverMovesMoney() {
    Company company = newCompany();
    BigDecimal senderBefore = balance(company.account());
    long approvalId = holdSameBank(company, "100.00");

    given(sessionSpec(company.approver()))
        .post(APPROVALS + "/" + approvalId + "?command=reject")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("REJECTED"));

    Response approveAfter =
        approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertEquals(400, approveAfter.statusCode(), approveAfter.asString());
    assertEquals("REJECTED", approvalStatus(approvalId));
    assertEquals(0, senderBefore.compareTo(balance(company.account())));
    assertEquals(0, transferCount(company.account()));
  }

  @Test
  @DisplayName("fails closed: without step-up on APPROVE_SECOND_SIGNATURE nothing is held or sent")
  void withoutApproverStepUp_transferIsRefused() {
    Company company = newCompany();
    BigDecimal senderBefore = balance(company.account());
    String token = confirmToken(company.initiator(), sameBankConfirm(company, "100.00"));
    setStepUpProperty("protectedActions", "CONFIRM_TRANSFER");

    Response response =
        given(withToken(company.initiator(), token))
            .body(sameBankConfirm(company, "100.00"))
            .post(TRANSFERS + "/confirm");

    assertEquals(400, response.statusCode(), response.asString());
    assertEquals(
        "validation.msg.transferapproval.approvalId.otp.not.configured", errorCode(response));
    assertEquals("0", approvalCount(company.account()));
    assertEquals(0, senderBefore.compareTo(balance(company.account())));
  }

  // ---------------------------------------------------------------------------------------------
  // PIN: count the calls that reach the external gateway
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("PIN: an approved transfer reaches the gateway once; a second approval never does")
  void pinTransfer_reachesTheGatewayOnce() {
    Company company = newCompany();
    long approvalId = holdPin(company, "100.00");
    assertEquals(0, pinTransferCalls(), "held: the gateway is not called");

    Response approved = approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertEquals(200, approved.statusCode(), approved.asString());
    assertEquals("PIN", approved.path("transferType"));
    assertEquals("APPROVED", approvalStatus(approvalId));
    assertEquals(1, pinTransferCalls());

    Response again = approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertEquals(400, again.statusCode(), again.asString());
    assertEquals(1, pinTransferCalls(), "the gateway must not be called a second time");
  }

  @Test
  @DisplayName("PIN: two signatories approving at once reach the gateway once")
  void pinTransfer_concurrentApprovals_reachTheGatewayOnce() throws Exception {
    Company company = newCompany();
    Session secondApprover = newPersonUser();
    executeSqlInPostgres(
        "INSERT INTO m_selfservice_entity_signatory (client_id, appuser_id, is_active, created_at)"
            + " VALUES (%s, %s, true, CURRENT_TIMESTAMP);",
        company.account().clientId(),
        secondApprover.userId());
    long approvalId = holdPin(company, "100.00");
    String firstToken = approvalToken(company.approver(), approvalId);
    String secondToken = approvalToken(secondApprover, approvalId);

    CyclicBarrier start = new CyclicBarrier(2);
    CompletableFuture<Response> first =
        CompletableFuture.supplyAsync(
            () -> approveAfter(start, company.approver(), approvalId, firstToken));
    CompletableFuture<Response> second =
        CompletableFuture.supplyAsync(
            () -> approveAfter(start, secondApprover, approvalId, secondToken));
    List<Response> responses = List.of(first.get(), second.get());

    List<Integer> codes = responses.stream().map(Response::statusCode).sorted().toList();
    assertEquals(
        List.of(200, 400), codes, responses.get(0).asString() + responses.get(1).asString());
    Response loser =
        responses.stream().filter(r -> r.statusCode() == 400).findFirst().orElseThrow();
    assertEquals("validation.msg.transferapproval.approvalId.already.decided", errorCode(loser));
    assertEquals("APPROVED", approvalStatus(approvalId));
    assertEquals(1, pinTransferCalls(), "the gateway must receive the transfer exactly once");
  }

  @Test
  @DisplayName("PIN: an empty gateway body is not a failure; the transfer is approved")
  void pinTransfer_emptyGatewayBody_isAccepted() {
    resetPinGateway("");
    Company company = newCompany();
    long approvalId = holdPin(company, "100.00");

    Response approved = approve(company.approver(), approvalId, approvalToken(company, approvalId));

    assertEquals(200, approved.statusCode(), approved.asString());
    assertEquals("APPROVED", approvalStatus(approvalId));
    assertEquals(1, pinTransferCalls());
  }

  @Test
  @DisplayName("PIN: a failure after the gateway accepted ends as FAILED and is never sent again")
  void pinTransfer_failureAfterTheSend_isFinal() {
    // The gateway accepts the transfer but answers with an amount that is not a number, so the
    // plugin fails after the money left. The approval must not go back to PENDING.
    resetPinGateway("{\"operationId\":\"PIN-OP-1\",\"debitedAmount\":\"n/a\"}");
    Company company = newCompany();
    long approvalId = holdPin(company, "100.00");

    Response failed = approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertTrue(failed.statusCode() >= 400, failed.asString());
    assertEquals("FAILED", approvalStatus(approvalId));
    assertEquals(1, pinTransferCalls());

    Response retry = approve(company.approver(), approvalId, approvalToken(company, approvalId));
    assertEquals(400, retry.statusCode(), retry.asString());
    assertEquals("validation.msg.transferapproval.approvalId.already.decided", errorCode(retry));
    assertEquals("FAILED", approvalStatus(approvalId));
    assertEquals(1, pinTransferCalls(), "a failed approval must never reach the gateway again");
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: transfers and approvals
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> sameBankConfirm(Company company, String amount) {
    Map<String, Object> body = new HashMap<>();
    body.put("fromAccount", String.valueOf(company.account().savingsId()));
    body.put("fromAccountType", 2);
    body.put("toAccount", String.valueOf(receiver.savingsId()));
    body.put("toAccountType", 2);
    body.put("transferType", "SAME_BANK");
    body.put("currencyCode", "USD");
    body.put("transferAmount", new BigDecimal(amount));
    body.put("transferDescription", "MX-489 dual approval integration test");
    return body;
  }

  private static Map<String, Object> pinConfirm(Company company, String amount) {
    Map<String, Object> body = sameBankConfirm(company, amount);
    body.put("toAccount", PIN_DESTINATION_IBAN);
    body.remove("toAccountType");
    body.put("transferType", "PIN");
    return body;
  }

  private static Response confirmSameBank(Company company, String amount) {
    Map<String, Object> body = sameBankConfirm(company, amount);
    return given(withToken(company.initiator(), confirmToken(company.initiator(), body)))
        .body(body)
        .post(TRANSFERS + "/confirm");
  }

  private static long holdSameBank(Company company, String amount) {
    return hold(company.initiator(), sameBankConfirm(company, amount));
  }

  private static long holdPin(Company company, String amount) {
    return hold(company.initiator(), pinConfirm(company, amount));
  }

  private static long hold(Session initiator, Map<String, Object> body) {
    Response held =
        given(withToken(initiator, confirmToken(initiator, body)))
            .body(body)
            .post(TRANSFERS + "/confirm");
    assertEquals(200, held.statusCode(), held.asString());
    assertEquals("PENDING_APPROVAL", held.path("status"), held.asString());
    return held.jsonPath().getLong("approvalId");
  }

  private static Response approve(Session approver, long approvalId, String token) {
    return given(withToken(approver, token))
        .post(APPROVALS + "/" + approvalId + "?command=approve");
  }

  /** Waits until the other request is ready too, so both approvals are sent at the same time. */
  private static Response approveAfter(
      CyclicBarrier start, Session approver, long approvalId, String token) {
    try {
      start.await(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("the concurrent approvals did not start together", e);
    }
    return approve(approver, approvalId, token);
  }

  private static String approvalStatus(long approvalId) {
    return querySingleValueInPostgres(
        "SELECT status FROM m_selfservice_transfer_approval WHERE id = " + approvalId);
  }

  private static String approvalCount(SeedResult account) {
    return querySingleValueInPostgres(
        "SELECT COUNT(*) FROM m_selfservice_transfer_approval WHERE client_id = "
            + account.clientId());
  }

  /** Account transfers Fineract booked out of {@code account}. */
  private static int transferCount(SeedResult account) {
    return Integer.parseInt(
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_account_transfer_details WHERE from_savings_account_id = "
                + account.savingsId()));
  }

  private static void assertMovedOnce(
      Company company, BigDecimal senderBefore, BigDecimal receiverBefore, String amount) {
    BigDecimal moved = new BigDecimal(amount);
    assertEquals(1, transferCount(company.account()), "exactly one transfer is booked");
    assertEquals(0, senderBefore.subtract(moved).compareTo(balance(company.account())));
    assertEquals(0, receiverBefore.add(moved).compareTo(balance(receiver)));
  }

  private static String errorCode(Response response) {
    return response.path("errors[0].userMessageGlobalisationCode");
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: step-up
  // ---------------------------------------------------------------------------------------------

  /** A verified CONFIRM_TRANSFER token bound to exactly this confirm body. */
  private static String confirmToken(Session initiator, Map<String, Object> confirm) {
    Map<String, Object> payload = new HashMap<>();
    for (String key :
        List.of("fromAccount", "fromAccountType", "toAccount", "toAccountType", "transferType")) {
      if (confirm.containsKey(key)) {
        payload.put(key, confirm.get(key));
      }
    }
    payload.put("currencyCode", confirm.get("currencyCode"));
    payload.put("transferAmount", confirm.get("transferAmount"));
    return approvedToken(initiator, "CONFIRM_TRANSFER", payload);
  }

  private static String approvalToken(Company company, long approvalId) {
    return approvalToken(company.approver(), approvalId);
  }

  private static String approvalToken(Session session, long approvalId) {
    return approvedToken(session, "APPROVE_SECOND_SIGNATURE", Map.of("approvalId", approvalId));
  }

  /** Creates a challenge, forces a known OTP, verifies it and returns the one-time token. */
  private static String approvedToken(Session session, String action, Map<String, Object> payload) {
    Response challenge =
        given(sessionSpec(session))
            .body(Map.of("action", action, "channel", "EMAIL", "payload", payload))
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

  private static String stepUpProperty(String name) {
    return querySingleValueInPostgres(
        "SELECT p.value FROM c_external_service_properties p JOIN c_external_service s ON"
            + " p.external_service_id = s.id WHERE s.name = 'SELF_SERVICE_STEP_UP' AND p.name = '"
            + name
            + "'");
  }

  private static void setStepUpProperty(String name, String value) {
    setExternalServiceProperty("SELF_SERVICE_STEP_UP", name, value);
  }

  private static void setDualApprovalEnabled(String value) {
    setExternalServiceProperty("SELF_SERVICE_DUAL_APPROVAL", "enabled", value);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: PIN gateway (WireMock)
  // ---------------------------------------------------------------------------------------------

  private static String pinProperty(String name) {
    return querySingleValueInPostgres(
        "SELECT p.value FROM c_external_service_properties p JOIN c_external_service s ON"
            + " p.external_service_id = s.id WHERE s.name = 'PinService' AND p.name = '"
            + name
            + "'");
  }

  private static void setPinProperty(String name, String value) {
    setExternalServiceProperty("PinService", name, value);
  }

  private static void setExternalServiceProperty(String service, String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name = %s);",
        value, name, service);
  }

  private static RequestSpecification gatewayAdmin() {
    return given()
        .baseUri("http://" + pinGateway.getHost())
        .port(pinGateway.getMappedPort(8080))
        .contentType("application/json");
  }

  /**
   * Clears recorded calls and stubs the two PIN calls of a transfer: the destination lookup and the
   * transfer itself, which answers with {@code transferBody}.
   */
  private static void resetPinGateway(String transferBody) {
    gatewayAdmin().post("/__admin/reset").then().statusCode(200);
    gatewayAdmin()
        .body(
            Map.of(
                "request",
                Map.of("method", "GET", "urlPathPattern", "/api/v1/accounts/.*/info"),
                "response",
                Map.of(
                    "status",
                    200,
                    "headers",
                    Map.of("Content-Type", "application/json"),
                    "jsonBody",
                    Map.of(
                        "holder", "PIN Receiver SA",
                        "holderId", "3101123456",
                        "holderIdType", "2",
                        "currencyCode", "USD"))))
        .post("/__admin/mappings")
        .then()
        .statusCode(201);
    gatewayAdmin()
        .body(
            Map.of(
                "request",
                Map.of("method", "POST", "urlPath", PIN_TRANSFER_PATH),
                "response",
                Map.of("status", 200, "body", transferBody)))
        .post("/__admin/mappings")
        .then()
        .statusCode(201);
  }

  private static int pinTransferCalls() {
    return gatewayAdmin()
        .body(Map.of("method", "POST", "urlPath", PIN_TRANSFER_PATH))
        .post("/__admin/requests/count")
        .then()
        .statusCode(200)
        .extract()
        .path("count");
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

  private static BigDecimal balance(SeedResult account) {
    Float value =
        given(admin())
            .get(CP + "/savingsaccounts/" + account.savingsId())
            .then()
            .statusCode(200)
            .extract()
            .path("summary.accountBalance");
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers: seeding
  // ---------------------------------------------------------------------------------------------

  /**
   * A funded ENTITY client with a USD threshold of {@link #THRESHOLD}, its owner (the initiator)
   * and a second user; both are active signatories of the client.
   */
  private static Company newCompany() {
    SeedResult account = seedClient("company", 2, true);
    Session initiator = newUser(account);
    Session approver = newPersonUser();
    executeSqlInPostgres(
        "INSERT INTO m_selfservice_client_approval_threshold (client_id, currency_code,"
            + " threshold_amount) VALUES (%s, 'USD', %s);"
            + " INSERT INTO m_selfservice_entity_signatory (client_id, appuser_id, is_active,"
            + " created_at) VALUES (%s, %s, true, CURRENT_TIMESTAMP), (%s, %s, true,"
            + " CURRENT_TIMESTAMP);",
        account.clientId(),
        THRESHOLD,
        account.clientId(),
        initiator.userId(),
        account.clientId(),
        approver.userId());
    return new Company(account, initiator, approver);
  }

  /** A self-service user who owns a client of their own and nothing else. */
  private static Session newPersonUser() {
    return newUser(seedClient("person", 1, false));
  }

  private static Session newUser(SeedResult account) {
    String username = insertSelfServiceUser(account.clientId(), account.officeId());
    Response response = SelfServiceTestUtils.authenticate(getFineractPort(), username, "password");
    assertEquals(200, response.statusCode(), response.asString());
    String key = response.path("base64EncodedAuthenticationKey");
    assertNotNull(key, "login must return a session key");
    Integer userId =
        Integer.valueOf(
            querySingleValueInPostgres(
                "SELECT id FROM m_appselfservice_user WHERE username = '" + username + "'"));
    return new Session(userId, "Basic " + key, account);
  }

  private static SeedResult seedClient(String label, int legalFormId, boolean funded) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> client = new HashMap<>();
    client.put("officeId", 1);
    client.put("legalFormId", legalFormId);
    if (legalFormId == 2) {
      client.put("fullname", label + " " + suffix + " SA");
    } else {
      client.put("firstname", label);
      client.put("lastname", suffix);
    }
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
    product.put("name", "DualApproval-Product-" + suffix);
    product.put("shortName", suffix.substring(0, 4));
    product.put("description", "MX-489 zero-fee savings product");
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

  private static String insertSelfServiceUser(Integer clientId, Integer officeId) {
    String username = "ssda_" + UUID.randomUUID().toString().substring(0, 8);
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
        'SS', 'DUALAPPROVAL', false, true, true, true, true, false
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
        'SS', 'DUALAPPROVAL', false, true, true, true, true, false, true, true, false
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
