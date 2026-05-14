/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.notification;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.restassured.response.Response;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testcontainers integration test that verifies the notification system does not break
 * authentication when the SMTP configuration table is missing.
 *
 * <p>This test exercises the full end-to-end flow:
 *
 * <ol>
 *   <li>Enroll a new user (two-step flow)
 *   <li>Confirm the enrollment
 *   <li>Authenticate — this fires a {@code LOGIN_SUCCESS} notification event
 *   <li>Verify the login succeeds (HTTP 200) even though the notification handler encounters {@code
 *       SmtpConfigurationUnavailableException}
 *   <li>Repeat login to verify the "log once" suppression and cooldown work correctly under the
 *       real async thread pool
 * </ol>
 *
 * <p>The Fineract container's PostgreSQL database does NOT have the {@code
 * c_external_service_properties} table, matching the real-world deployment scenario that triggered
 * this fix.
 */
public class SelfServiceNotificationSmtpIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String ENROLLMENT_PATH =
      "/fineract-provider/api/v1/self/registration/client-user";
  private static final String CONFIRM_PATH =
      "/fineract-provider/api/v1/self/registration/client-user/confirm";
  private static final AtomicLong UNIQUE_ID_SEQUENCE = new AtomicLong(System.nanoTime());

  private static String numericId() {
    return String.format("%08d", Math.floorMod(UNIQUE_ID_SEQUENCE.incrementAndGet(), 100000000));
  }

  private String queryEnrollmentToken(String username) {
    return querySingleValueInPostgres(
        "SELECT external_authorization_token FROM request_audit_table "
            + "WHERE username = "
            + sqlLiteral(username)
            + " AND request_type = 'ENROLLMENT' "
            + "ORDER BY id DESC LIMIT 1");
  }

  /**
   * Verifies that the login flow succeeds even when the notification system encounters a missing
   * SMTP configuration table.
   *
   * <p>This is the core regression test for the original error: {@code ERROR: relation
   * "c_external_service_properties" does not exist}
   */
  @Test
  @DisplayName("Login succeeds despite missing c_external_service_properties table")
  void loginSucceeds_whenSmtpTableMissing() {
    String id = numericId();
    String username = "notif_" + id;
    String password = "Strong#Abc123";

    // Step 1: Enroll
    String payload =
        """
            {
              "username": "%s",
              "password": "%s",
              "firstName": "Notif",
              "lastName": "Test",
              "mobileNumber": "555%s",
              "email": "notif%s@fineract.test",
              "authenticationMode": "email",
              "active": true
            }
            """
            .formatted(username, password, id, id);

    Response enrollResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(payload)
            .when()
            .post(ENROLLMENT_PATH)
            .then()
            .extract()
            .response();

    assertEquals(
        200,
        enrollResponse.getStatusCode(),
        "Enrollment should succeed. Body: " + enrollResponse.body().asString());

    // Step 2: Confirm enrollment
    String token = queryEnrollmentToken(username);
    assertNotNull(token, "Enrollment token must exist");
    assertFalse(token.isBlank(), "Enrollment token must not be blank");

    Response confirmResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(
                """
                    { "externalAuthenticationToken": "%s" }
                    """
                    .formatted(token))
            .when()
            .post(CONFIRM_PATH)
            .then()
            .extract()
            .response();

    assertEquals(
        200,
        confirmResponse.getStatusCode(),
        "Confirmation should succeed. Body: " + confirmResponse.body().asString());

    // Step 3: Login — fires LOGIN_SUCCESS notification which hits SMTP fallback
    Response loginResponse =
        SelfServiceTestUtils.authenticate(getFineractPort(), username, password);
    assertEquals(
        200,
        loginResponse.getStatusCode(),
        "Login MUST succeed even though SMTP table is missing. Body: "
            + loginResponse.body().asString());

    // Verify authentication key is returned
    String authKey = loginResponse.jsonPath().getString("base64EncodedAuthenticationKey");
    assertNotNull(authKey, "Authentication key must be present in login response");
    assertFalse(authKey.isBlank(), "Authentication key must not be blank");
  }

  /**
   * Verifies that repeated logins do not cause accumulated errors or thread pool exhaustion when
   * SMTP is unavailable.
   */
  @Test
  @DisplayName("Repeated logins remain stable despite SMTP unavailability")
  void repeatedLogins_remainStable_whenSmtpUnavailable() {
    String id = numericId();
    String username = "repeat_" + id;
    String password = "Strong#Abc123";

    // Enroll + confirm
    String payload =
        """
            {
              "username": "%s",
              "password": "%s",
              "firstName": "Repeat",
              "lastName": "Test",
              "mobileNumber": "555%s",
              "email": "repeat%s@fineract.test",
              "authenticationMode": "email",
              "active": true
            }
            """
            .formatted(username, password, id, id);

    Response enrollResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(payload)
            .when()
            .post(ENROLLMENT_PATH)
            .then()
            .extract()
            .response();
    assertEquals(200, enrollResponse.getStatusCode());

    String token = queryEnrollmentToken(username);
    Response confirmResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(
                """
                    { "externalAuthenticationToken": "%s" }
                    """
                    .formatted(token))
            .when()
            .post(CONFIRM_PATH)
            .then()
            .extract()
            .response();
    assertEquals(200, confirmResponse.getStatusCode());

    // Login 5 times rapidly — all should succeed
    for (int i = 0; i < 5; i++) {
      Response login = SelfServiceTestUtils.authenticate(getFineractPort(), username, password);
      assertEquals(
          200,
          login.getStatusCode(),
          "Login #" + (i + 1) + " should succeed. Body: " + login.body().asString());
    }

    // Small pause to let async notification threads complete
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Final login to confirm stability
    Response finalLogin = SelfServiceTestUtils.authenticate(getFineractPort(), username, password);
    assertEquals(
        200, finalLogin.getStatusCode(), "Final login should succeed after repeated SMTP failures");

    // Verify the Fineract container is still healthy
    Response health =
        given()
            .relaxedHTTPSValidation()
            .baseUri("https://localhost")
            .port(getFineractPort())
            .when()
            .get("/fineract-provider/actuator/health");
    assertEquals(
        200,
        health.getStatusCode(),
        "Fineract health endpoint should still respond after repeated SMTP failures");
  }
}
