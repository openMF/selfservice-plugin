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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.apache.fineract.selfservice.stepup.totp.Base32;
import org.apache.fineract.selfservice.stepup.totp.Totp;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authenticator app (TOTP) as a step-up channel, against a real Fineract + PostgreSQL started
 * by Testcontainers with the plugin on the classpath.
 *
 * <p>The code of an SMS or e-mail OTP is not readable from the test (only its salted HMAC is
 * stored), so the test replaces the stored hash with the HMAC of a known code, as {@code
 * SelfStepUpIntegrationTest} does. The codes of the authenticator app are computed here from the
 * secret that the API returned at pairing, the way a real app does.
 *
 * <p>All integration test classes share one Fineract container and one tenant database, so every
 * setting this class changes is restored in {@link #restoreDefaults()}.
 */
class SelfTotpIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String DATE_FORMAT = "dd MMMM yyyy";
  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH);
  private static final String CP = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1";
  private static final String STEPUP = CP + "/self/stepup";
  private static final String TOTP = STEPUP + "/totp";
  private static final String ACTIVITY = CP + "/self/activity";
  private static final String ADMIN_USERS = CP + "/selfservice/users";
  private static final String KNOWN_OTP = "123456";
  private static final String TOTP_KEY = "it-totp-encryption-key-it-totp-key-123456";

  private static Integer roleId;

  private record Session(String username, long userId, String authHeader) {}

  /** What the user's phone knows after pairing. */
  private record Paired(Session session, byte[] secret, List<String> recoveryCodes) {}

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
  }

  @BeforeEach
  void authenticatorOn() {
    setStepUpProperty("hmacSecret", STEP_UP_HMAC_SECRET);
    setStepUpProperty("enabled", "true");
    setStepUpProperty("challengeRateLimit", "50");
    setStepUpProperty("totpEncryptionKey", TOTP_KEY);
    setStepUpProperty("totpEnabled", "true");
    setStepUpProperty("totpExclusive", "true");
    setStepUpProperty("totpMaxFailures", "5");
    setStepUpProperty("totpLockoutSeconds", "900");
    setActivityProperty("enabled", "true");
  }

  /** The tenant settings are shared with the other test classes: leave them as found. */
  @AfterAll
  static void restoreDefaults() {
    setStepUpProperty("enabled", "false");
    setStepUpProperty("challengeRateLimit", "5");
    setStepUpProperty("hmacSecret", "change-me-before-enabling");
    setStepUpProperty("totpEnabled", "false");
    setStepUpProperty("totpEncryptionKey", "change-me-before-enabling");
    setStepUpProperty("totpExclusive", "true");
    setStepUpProperty("totpMaxFailures", "5");
    setStepUpProperty("totpLockoutSeconds", "900");
  }

  // ---------------------------------------------------------------------------------------------
  // off by default
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the tenant did not switch it on: nothing is offered and nothing can be paired")
  void switchedOff_nothingIsOfferedAndNothingCanBePaired() {
    setStepUpProperty("totpEnabled", "false");
    Session session = newUser();

    given(sessionSpec(session))
        .get(TOTP)
        .then()
        .statusCode(200)
        .body("available", org.hamcrest.Matchers.equalTo(false))
        .body("status", org.hamcrest.Matchers.equalTo("NONE"));
    given(sessionSpec(session))
        .post(TOTP + "/enrollment")
        .then()
        .statusCode(400)
        .body(
            "userMessageGlobalisationCode",
            org.hamcrest.Matchers.equalTo("error.msg.selfservice.stepup.totp.disabled"));
    given(sessionSpec(session))
        .body(Map.of("action", "CHANGE_PASSWORD", "channel", "TOTP"))
        .post(STEPUP + "/challenges")
        .then()
        .statusCode(400)
        .body(
            "userMessageGlobalisationCode",
            org.hamcrest.Matchers.equalTo("error.msg.selfservice.stepup.channel.unavailable"));

    // the existing channels work exactly as before
    assertEquals(
        List.of("EMAIL"),
        given(sessionSpec(session)).get(STEPUP + "/config").path("availableChannels"));
    assertEquals(201, challenge(session, "CHANGE_PASSWORD", "EMAIL").statusCode());
  }

  @Test
  @DisplayName("the key is a placeholder: pairing fails closed instead of storing a weak secret")
  void withoutARealKey_pairingFailsClosed() {
    setStepUpProperty("totpEncryptionKey", "change-me-before-enabling");
    Session session = newUser();

    given(sessionSpec(session))
        .get(TOTP)
        .then()
        .statusCode(200)
        .body("available", org.hamcrest.Matchers.equalTo(false));
    given(sessionSpec(session))
        .post(TOTP + "/enrollment")
        .then()
        .statusCode(500)
        .body(
            "userMessageGlobalisationCode",
            org.hamcrest.Matchers.equalTo("error.msg.selfservice.stepup.misconfigured"));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_totp_enrollment WHERE appuser_id = "
                + session.userId()));
  }

  // ---------------------------------------------------------------------------------------------
  // pairing
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("pairing: the secret is shown once, stored encrypted, and needs a step-up")
  void pairing_theSecretIsShownOnce_storedEncrypted_andNeedsAStepUp() {
    Session session = newUser();

    Response begin = given(sessionSpec(session)).post(TOTP + "/enrollment");
    assertEquals(201, begin.statusCode(), begin.asString());
    String secretText = begin.path("secret");
    assertTrue(secretText.matches("[A-Z2-7]{32}"), secretText);
    assertTrue(((String) begin.path("otpauthUri")).startsWith("otpauth://totp/Mifos:"));
    assertEquals(6, (int) begin.path("digits"));
    assertEquals(30, (int) begin.path("periodSeconds"));

    // the database holds neither the secret nor anything that contains it
    String stored =
        querySingleValueInPostgres(
            "SELECT secret_enc FROM m_selfservice_totp_enrollment WHERE appuser_id = "
                + session.userId());
    assertFalse(stored.contains(secretText));
    assertEquals(
        "PENDING",
        querySingleValueInPostgres(
            "SELECT status FROM m_selfservice_totp_enrollment WHERE appuser_id = "
                + session.userId()));

    // no call returns the secret again
    Response status = given(sessionSpec(session)).get(TOTP);
    assertEquals("PENDING", status.path("status"));
    assertFalse(status.asString().contains(secretText));
    assertFalse(
        given(sessionSpec(session)).get(STEPUP + "/config").asString().contains(secretText));

    byte[] secret = Base32.decode(secretText);
    String code = Totp.code(secret, currentStep());

    // a stolen session cannot pair its own phone: the right code alone is not enough
    Response noToken =
        given(sessionSpec(session)).body(Map.of("code", code)).post(TOTP + "/enrollment/confirm");
    assertEquals(403, noToken.statusCode(), noToken.asString());
    assertEquals(
        "error.msg.selfservice.stepup.required", noToken.path("userMessageGlobalisationCode"));
    assertEquals("MANAGE_AUTHENTICATOR", noToken.path("action"));
    assertEquals("PENDING", status(session));

    // a token for another action is not accepted either
    String wrongActionToken = approvedToken(session, "CHANGE_PASSWORD", "EMAIL", null);
    Response wrongAction =
        given(withToken(session, wrongActionToken))
            .body(Map.of("code", code))
            .post(TOTP + "/enrollment/confirm");
    assertEquals(403, wrongAction.statusCode(), wrongAction.asString());
    assertEquals(
        "error.msg.selfservice.stepup.invalid", wrongAction.path("userMessageGlobalisationCode"));

    // a wrong code is refused (and the token is not burned)
    String token = approvedToken(session, "MANAGE_AUTHENTICATOR", "EMAIL", null);
    Response wrong =
        given(withToken(session, token))
            .body(Map.of("code", wrongCode(secret)))
            .post(TOTP + "/enrollment/confirm");
    assertEquals(400, wrong.statusCode(), wrong.asString());
    assertEquals(
        "error.msg.selfservice.stepup.otp.invalid", wrong.path("userMessageGlobalisationCode"));
    assertEquals(4, (int) wrong.path("attemptsRemaining"));
    assertEquals("PENDING", status(session));

    // step-up token + a code of the app: paired
    Response ok =
        given(withToken(session, token))
            .body(Map.of("code", code))
            .post(TOTP + "/enrollment/confirm");
    assertEquals(200, ok.statusCode(), ok.asString());
    List<String> recovery = ok.path("recoveryCodes");
    assertEquals(10, recovery.size());
    recovery.forEach(c -> assertTrue(c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"), c));

    Response active = given(sessionSpec(session)).get(TOTP);
    assertEquals("ACTIVE", active.path("status"));
    assertEquals(10, (int) active.path("recoveryCodesRemaining"));
    assertFalse(active.asString().contains(recovery.get(0)));

    // the recovery codes are stored only as keyed hashes
    assertEquals(
        "10",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_totp_recovery_code WHERE appuser_id = "
                + session.userId()
                + " AND code_hash ~ '^[0-9a-f]{64}$'"));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_totp_recovery_code WHERE code_hash = "
                + sqlLiteral(recovery.get(0).replace("-", ""))));

    // the token was used up by the pairing
    Response again =
        given(withToken(session, token))
            .body(Map.of("code", code))
            .post(TOTP + "/enrollment/confirm");
    assertEquals(409, again.statusCode(), again.asString());
  }

  @Test
  @DisplayName("an active authenticator is never replaced silently")
  void anActiveAuthenticator_cannotBeReplacedWithoutRemovingIt() {
    Paired paired = pair(newUser());

    Response again = given(sessionSpec(paired.session())).post(TOTP + "/enrollment");

    assertEquals(409, again.statusCode(), again.asString());
    assertEquals(
        "error.msg.selfservice.stepup.totp.already.enrolled",
        again.path("userMessageGlobalisationCode"));
    assertEquals("ACTIVE", status(paired.session()));
  }

  // ---------------------------------------------------------------------------------------------
  // using it
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("exclusive: with an authenticator only TOTP is offered, so a SIM swap does not help")
  void exclusive_onlyTheAuthenticatorIsOffered() {
    Paired paired = pair(newUser());

    assertEquals(
        List.of("TOTP"),
        given(sessionSpec(paired.session())).get(STEPUP + "/config").path("availableChannels"));
    for (String channel : List.of("EMAIL", "SMS")) {
      Response refused = challenge(paired.session(), "CHANGE_PASSWORD", channel);
      assertEquals(400, refused.statusCode(), refused.asString());
      assertEquals(
          "error.msg.selfservice.stepup.channel.unavailable",
          refused.path("userMessageGlobalisationCode"));
    }
  }

  @Test
  @DisplayName("not exclusive: SMS and e-mail stay available next to the authenticator")
  void notExclusive_theOtherChannelsStayAvailable() {
    Paired paired = pair(newUser());
    setStepUpProperty("totpExclusive", "false");

    List<String> channels =
        given(sessionSpec(paired.session())).get(STEPUP + "/config").path("availableChannels");
    assertTrue(channels.contains("TOTP"));
    assertTrue(channels.contains("EMAIL"));
    assertEquals(201, challenge(paired.session(), "CHANGE_PASSWORD", "EMAIL").statusCode());
  }

  @Test
  @DisplayName(
      "TOTP challenge: nothing is sent or stored, the code of the app gives a one-time grant")
  void totpChallenge_givesAOneTimeGrant_boundToTheAction() {
    Paired paired = pair(newUser());
    Session session = paired.session();

    Response created = challenge(session, "CHANGE_PASSWORD", "TOTP");
    assertEquals(201, created.statusCode(), created.asString());
    assertEquals("TOTP", created.path("channel"));
    assertNull(created.path("maskedDestination"));
    assertEquals(0, (int) created.path("resendsRemaining"));
    long challengeId = created.jsonPath().getLong("challengeId");
    assertEquals(
        "1",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_stepup_challenge WHERE id = "
                + challengeId
                + " AND otp_hash IS NULL AND otp_salt IS NULL AND channel = 'TOTP'"));

    // a code of the app, one step after the pairing code (that step is used up)
    Response verified =
        given(sessionSpec(session))
            .body(Map.of("otp", Totp.code(paired.secret(), currentStep() + 1)))
            .post(STEPUP + "/challenges/" + challengeId + "/verify");
    assertEquals(200, verified.statusCode(), verified.asString());
    String token = verified.path("stepUpToken");
    assertNotNull(token);
    assertEquals("CHANGE_PASSWORD", verified.path("action"));

    // bound to the action: it does not open the management of the authenticator
    Response wrongUse = given(withToken(session, token)).delete(TOTP);
    assertEquals(403, wrongUse.statusCode(), wrongUse.asString());
    assertEquals("ACTIVE", status(session));
  }

  @Test
  @DisplayName("replay: the code of a step that was used is refused, and the refusal is counted")
  void replay_aCodeOfAUsedStep_isRefused() {
    Paired paired = pair(newUser());
    Session session = paired.session();
    String code = Totp.code(paired.secret(), currentStep() + 1);

    long first = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    assertEquals(
        200,
        given(sessionSpec(session))
            .body(Map.of("otp", code))
            .post(STEPUP + "/challenges/" + first + "/verify")
            .statusCode());

    long second = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    Response replay =
        given(sessionSpec(session))
            .body(Map.of("otp", code))
            .post(STEPUP + "/challenges/" + second + "/verify");
    assertEquals(400, replay.statusCode(), replay.asString());
    assertEquals(
        "error.msg.selfservice.stepup.otp.invalid", replay.path("userMessageGlobalisationCode"));
  }

  @Test
  @DisplayName(
      "lock-out: wrong codes lock the authenticator across challenges, even for the right code")
  void lockout_wrongCodesLockTheAuthenticator_acrossChallenges() {
    Paired paired = pair(newUser());
    Session session = paired.session();
    String right = Totp.code(paired.secret(), currentStep() + 1);
    String wrong = wrongCode(paired.secret());

    // each wrong code is tried on a NEW challenge: opening challenges must not reset the count
    for (int attempt = 1; attempt <= 4; attempt++) {
      long id = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
      Response response =
          given(sessionSpec(session))
              .body(Map.of("otp", wrong))
              .post(STEPUP + "/challenges/" + id + "/verify");
      assertEquals(400, response.statusCode(), response.asString());
    }
    long id = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    Response locked =
        given(sessionSpec(session))
            .body(Map.of("otp", wrong))
            .post(STEPUP + "/challenges/" + id + "/verify");
    assertEquals(429, locked.statusCode(), locked.asString());
    assertEquals(
        "error.msg.selfservice.stepup.locked", locked.path("userMessageGlobalisationCode"));
    assertNotNull(locked.header("Retry-After"));

    // locked: the right code does not pass, and no new TOTP challenge is opened
    Response stillLocked =
        given(sessionSpec(session))
            .body(Map.of("otp", right))
            .post(STEPUP + "/challenges/" + id + "/verify");
    assertEquals(429, stillLocked.statusCode(), stillLocked.asString());
    assertEquals(429, challenge(session, "CHANGE_PASSWORD", "TOTP").statusCode());

    awaitType(session, "AUTHENTICATOR_LOCKED");
  }

  // ---------------------------------------------------------------------------------------------
  // recovery codes
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a recovery code gets in once and is then gone")
  void recoveryCode_worksOnce() {
    Paired paired = pair(newUser());
    Session session = paired.session();
    String recovery = paired.recoveryCodes().get(0);

    long first = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    Response ok =
        given(sessionSpec(session))
            .body(Map.of("recoveryCode", recovery.toLowerCase(Locale.ROOT)))
            .post(STEPUP + "/challenges/" + first + "/verify");
    assertEquals(200, ok.statusCode(), ok.asString());
    assertNotNull(ok.path("stepUpToken"));
    assertEquals(9, (int) given(sessionSpec(session)).get(TOTP).path("recoveryCodesRemaining"));

    long second = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    Response used =
        given(sessionSpec(session))
            .body(Map.of("recoveryCode", recovery))
            .post(STEPUP + "/challenges/" + second + "/verify");
    assertEquals(400, used.statusCode(), used.asString());

    // another, unused one still works
    long third = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    assertEquals(
        200,
        given(sessionSpec(session))
            .body(Map.of("recoveryCode", paired.recoveryCodes().get(1)))
            .post(STEPUP + "/challenges/" + third + "/verify")
            .statusCode());

    awaitType(session, "RECOVERY_CODE_USED");
  }

  @Test
  @DisplayName("a recovery code is not accepted instead of an SMS or e-mail code")
  void recoveryCode_isNotAnAlternativeToASmsOrEmailCode() {
    setStepUpProperty("totpExclusive", "false");
    Paired paired = pair(newUser());
    Session session = paired.session();

    long id = challengeId(challenge(session, "CHANGE_PASSWORD", "EMAIL"));
    Response response =
        given(sessionSpec(session))
            .body(Map.of("recoveryCode", paired.recoveryCodes().get(0)))
            .post(STEPUP + "/challenges/" + id + "/verify");

    assertEquals(400, response.statusCode(), response.asString());
    assertEquals(9 + 1, (int) given(sessionSpec(session)).get(TOTP).path("recoveryCodesRemaining"));
  }

  @Test
  @DisplayName("new recovery codes replace the old ones, behind a step-up")
  void regenerate_replacesTheOldCodes_behindAStepUp() {
    Paired paired = pair(newUser());
    Session session = paired.session();

    Response noToken = given(sessionSpec(session)).post(TOTP + "/recovery-codes");
    assertEquals(403, noToken.statusCode(), noToken.asString());

    String token = approvedToken(session, "MANAGE_AUTHENTICATOR", "TOTP", paired.secret());
    Response fresh = given(withToken(session, token)).post(TOTP + "/recovery-codes");
    assertEquals(200, fresh.statusCode(), fresh.asString());
    List<String> codes = fresh.path("recoveryCodes");
    assertEquals(10, codes.size());
    assertFalse(codes.contains(paired.recoveryCodes().get(0)));

    long id = challengeId(challenge(session, "CHANGE_PASSWORD", "TOTP"));
    assertEquals(
        400,
        given(sessionSpec(session))
            .body(Map.of("recoveryCode", paired.recoveryCodes().get(0)))
            .post(STEPUP + "/challenges/" + id + "/verify")
            .statusCode());
    assertEquals(
        200,
        given(sessionSpec(session))
            .body(Map.of("recoveryCode", codes.get(0)))
            .post(STEPUP + "/challenges/" + id + "/verify")
            .statusCode());
  }

  // ---------------------------------------------------------------------------------------------
  // removing it
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "removal needs a step-up with the authenticator itself, and gives SMS and e-mail back")
  void removal_needsAStepUp_andGivesTheOtherChannelsBack() {
    Paired paired = pair(newUser());
    Session session = paired.session();

    Response noToken = given(sessionSpec(session)).delete(TOTP);
    assertEquals(403, noToken.statusCode(), noToken.asString());
    assertEquals("ACTIVE", status(session));

    String token = approvedToken(session, "MANAGE_AUTHENTICATOR", "TOTP", paired.secret());
    Response removed = given(withToken(session, token)).delete(TOTP);
    assertEquals(200, removed.statusCode(), removed.asString());

    assertEquals("NONE", status(session));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_totp_enrollment WHERE appuser_id = "
                + session.userId()));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_totp_recovery_code WHERE appuser_id = "
                + session.userId()));
    assertEquals(
        List.of("EMAIL"),
        given(sessionSpec(session)).get(STEPUP + "/config").path("availableChannels"));
    assertEquals(201, challenge(session, "CHANGE_PASSWORD", "EMAIL").statusCode());

    awaitType(session, "AUTHENTICATOR_REMOVED");
  }

  @Test
  @DisplayName("cancelling a pairing that was never confirmed needs no step-up")
  void cancellingAnUnconfirmedPairing_needsNoStepUp() {
    Session session = newUser();
    assertEquals(201, given(sessionSpec(session)).post(TOTP + "/enrollment").statusCode());

    assertEquals(200, given(sessionSpec(session)).delete(TOTP).statusCode());
    assertEquals("NONE", status(session));
  }

  @Test
  @DisplayName(
      "staff reset: a member of staff removes the authenticator of a user who lost the phone")
  void staffReset_removesTheAuthenticator_andTheStaffAddressIsNotKept() {
    Paired paired = pair(newUser());
    Session session = paired.session();

    // a self-service user cannot use the administration call
    given(sessionSpec(session))
        .delete(ADMIN_USERS + "/" + session.userId() + "/totp")
        .then()
        .statusCode(
            org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.equalTo(401), org.hamcrest.Matchers.equalTo(403)));
    assertEquals("ACTIVE", status(session));

    given(admin())
        .delete(ADMIN_USERS + "/" + session.userId() + "/totp")
        .then()
        .statusCode(200)
        .body("removed", org.hamcrest.Matchers.equalTo(true));

    assertEquals("NONE", status(session));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_totp_recovery_code WHERE appuser_id = "
                + session.userId()));
    assertEquals(
        List.of("EMAIL"),
        given(sessionSpec(session)).get(STEPUP + "/config").path("availableChannels"));

    // the user sees it in their history, and the address of the staff member is not stored
    awaitType(session, "AUTHENTICATOR_RESET");
    assertNull(
        nullIfEmpty(
            querySingleValueInPostgres(
                "SELECT COALESCE(ip_masked, '') FROM m_selfservice_activity WHERE appuser_id = "
                    + session.userId()
                    + " AND activity_type = 'AUTHENTICATOR_RESET'")));

    // nothing left to remove
    given(admin())
        .delete(ADMIN_USERS + "/" + session.userId() + "/totp")
        .then()
        .statusCode(200)
        .body("removed", org.hamcrest.Matchers.equalTo(false));
  }

  @Test
  @DisplayName("staff reset works even when the tenant switched the feature off")
  void staffReset_worksWhenTheFeatureIsSwitchedOff() {
    Paired paired = pair(newUser());
    setStepUpProperty("totpEnabled", "false");

    given(admin())
        .delete(ADMIN_USERS + "/" + paired.session().userId() + "/totp")
        .then()
        .statusCode(200)
        .body("removed", org.hamcrest.Matchers.equalTo(true));
  }

  // ---------------------------------------------------------------------------------------------
  // privacy and history
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a user cannot touch the authenticator of another user")
  void aUserCannotTouchTheAuthenticatorOfAnotherUser() {
    Paired alice = pair(newUser());
    Session bob = newUser();

    assertEquals("NONE", status(bob));
    assertEquals("ACTIVE", status(alice.session()));
    // bob's calls act on bob only: removing "his" authenticator does not affect alice
    assertEquals(400, given(sessionSpec(bob)).delete(TOTP).statusCode());
    assertEquals("ACTIVE", status(alice.session()));
    // alice's recovery codes and challenges are not usable from bob's session
    long aliceChallenge = challengeId(challenge(alice.session(), "CHANGE_PASSWORD", "TOTP"));
    assertEquals(
        403,
        given(sessionSpec(bob))
            .body(Map.of("otp", Totp.code(alice.secret(), currentStep() + 1)))
            .post(STEPUP + "/challenges/" + aliceChallenge + "/verify")
            .statusCode());
  }

  @Test
  @DisplayName("the history shows pairing and never a code, a secret or a hash")
  void history_showsThePairing_neverASecret() {
    Paired paired = pair(newUser());

    Response feed = awaitType(paired.session(), "AUTHENTICATOR_ENROLLED");

    String body = feed.asString();
    assertFalse(body.contains(Base32.encode(paired.secret())));
    paired.recoveryCodes().forEach(c -> assertFalse(body.contains(c)));
    Map<String, Object> entry = firstOfType(feed, "AUTHENTICATOR_ENROLLED");
    assertEquals("SUCCESS", entry.get("outcome"));
    // nothing about the pairing is kept as a detail: the server answers an empty object
    Object details = entry.get("details");
    assertTrue(details == null || ((Map<?, ?>) details).isEmpty(), String.valueOf(details));
    assertEquals(
        "0",
        querySingleValueInPostgres(
            "SELECT COUNT(*) FROM m_selfservice_activity WHERE details IS NOT NULL AND appuser_id ="
                + " "
                + paired.session().userId()
                + " AND activity_type LIKE 'AUTHENTICATOR%'"));
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static String nullIfEmpty(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static long currentStep() {
    return Totp.stepOf(Instant.now().getEpochSecond());
  }

  /** A 6-digit code that is not the one of any step the server accepts right now. */
  private static String wrongCode(byte[] secret) {
    long now = currentStep();
    for (int candidate = 0; candidate < 1_000_000; candidate++) {
      String text = String.format("%06d", candidate);
      boolean accepted = false;
      for (long step = now - 3; step <= now + 3; step++) {
        accepted |= Totp.code(secret, step).equals(text);
      }
      if (!accepted) {
        return text;
      }
    }
    throw new IllegalStateException("no wrong code found");
  }

  private static String status(Session session) {
    return given(sessionSpec(session)).get(TOTP).path("status");
  }

  private static long challengeId(Response created) {
    assertEquals(201, created.statusCode(), created.asString());
    return created.jsonPath().getLong("challengeId");
  }

  private static Response challenge(Session session, String action, String channel) {
    return given(sessionSpec(session))
        .body(Map.of("action", action, "channel", channel))
        .post(STEPUP + "/challenges");
  }

  /**
   * A one-time token for {@code action}. By e-mail the code is forced to a known one; by TOTP it is
   * computed from the secret of the app. Pairing used the current 30-second step and the server
   * accepts one step ahead, so a paired user can pass one TOTP verification right after pairing.
   */
  private static String approvedToken(
      Session session, String action, String channel, byte[] secret) {
    long id = challengeId(challenge(session, action, channel));
    String otp;
    if ("TOTP".equals(channel)) {
      otp = Totp.code(secret, currentStep() + 1);
    } else {
      overwriteOtp(id, KNOWN_OTP);
      otp = KNOWN_OTP;
    }
    Response verified =
        given(sessionSpec(session))
            .body(Map.of("otp", otp))
            .post(STEPUP + "/challenges/" + id + "/verify");
    assertEquals(200, verified.statusCode(), verified.asString());
    return verified.path("stepUpToken");
  }

  /** Pairs an authenticator the way a client does, and returns what the phone would know. */
  private static Paired pair(Session session) {
    Response begin = given(sessionSpec(session)).post(TOTP + "/enrollment");
    assertEquals(201, begin.statusCode(), begin.asString());
    byte[] secret = Base32.decode(begin.path("secret"));
    String token = approvedToken(session, "MANAGE_AUTHENTICATOR", "EMAIL", null);
    long step = currentStep();
    Response confirm =
        given(withToken(session, token))
            .body(Map.of("code", Totp.code(secret, step)))
            .post(TOTP + "/enrollment/confirm");
    assertEquals(200, confirm.statusCode(), confirm.asString());
    return new Paired(session, secret, confirm.path("recoveryCodes"));
  }

  private static Session newUser() {
    Integer clientId = seedClient();
    String username = insertSelfServiceUser(clientId);
    return loginWith(username, "password");
  }

  private static Session loginWith(String username, String password) {
    Response response =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(Map.of("username", username, "password", password))
            .post(SelfServiceTestUtils.SELF_AUTH_PATH);
    assertEquals(200, response.statusCode(), response.asString());
    String key = response.path("base64EncodedAuthenticationKey");
    assertNotNull(key, "login must return a session key");
    long userId =
        Long.parseLong(
            querySingleValueInPostgres(
                "SELECT id FROM m_appselfservice_user WHERE username = " + sqlLiteral(username)));
    return new Session(username, userId, "Basic " + key);
  }

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

  private static Response feed(Session session) {
    return given(sessionSpec(session)).get(ACTIVITY);
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
      last = feed(session);
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

  private static void setStepUpProperty(String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name ="
            + " 'SELF_SERVICE_STEP_UP');",
        value, name);
  }

  private static void setActivityProperty(String name, String value) {
    executeSqlInPostgres(
        "UPDATE c_external_service_properties SET value = %s WHERE name = %s AND"
            + " external_service_id = (SELECT id FROM c_external_service WHERE name ="
            + " 'SELF_SERVICE_ACTIVITY');",
        value, name);
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

  private static Integer seedClient() {
    String today = LocalDate.now(ZoneId.of("UTC")).format(FORMATTER);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> client = new HashMap<>();
    client.put("officeId", 1);
    client.put("legalFormId", 1);
    client.put("firstname", "totp");
    client.put("lastname", suffix);
    client.put("externalId", "totp-" + suffix);
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

  private static String insertSelfServiceUser(Integer clientId) {
    String username = "sstotp_" + UUID.randomUUID().toString().substring(0, 8);
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
        SELECT id, 1, %1$s, (SELECT password FROM m_appuser WHERE username = 'mifos' LIMIT 1),
            %2$s, 'SS', 'TOTP', false, true, true, true, true, false, true, true, false
        FROM new_id;

        INSERT INTO m_appselfservice_user_role(appuser_id, role_id)
        SELECT id, %3$s FROM new_id;

        INSERT INTO m_selfservice_user_client_mapping(appuser_id, client_id)
        SELECT id, %4$s FROM new_id;

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
                sqlLiteral(username),
                sqlLiteral(username + "@fineract.org"),
                sqlLiteral(roleId),
                sqlLiteral(clientId)));
    return username;
  }
}
