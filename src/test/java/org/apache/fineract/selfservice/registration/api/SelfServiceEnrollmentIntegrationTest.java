/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the two-step self-enrollment flow:
 *
 * <ol>
 *   <li>{@code POST /self/registration/client-user} — creates client + disabled user + sends token
 *   <li>{@code POST /self/registration/client-user/confirm} — validates token and enables user
 * </ol>
 */
public class SelfServiceEnrollmentIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String ENROLLMENT_PATH =
      "/fineract-provider/api/v1/self/registration/client-user";
  private static final String CONFIRM_PATH =
      "/fineract-provider/api/v1/self/registration/client-user/confirm";
  private static final long CONCURRENT_ENROLLMENT_START_TIMEOUT_SECONDS = 5;
  private static final AtomicLong UNIQUE_ID_SEQUENCE = new AtomicLong(System.nanoTime());

  /**
   * Generates a unique, digits-only suffix for phone numbers. A process-local atomic sequence
   * avoids collisions better than hashing UUIDs into a small range.
   */
  private static String numericId() {
    return String.format("%08d", Math.floorMod(UNIQUE_ID_SEQUENCE.incrementAndGet(), 100000000));
  }

  /**
   * Builds an enrollment payload that reuses a specific mobile suffix to trigger duplicate-number
   * scenarios.
   *
   * @param phone the mobile suffix shared across requests
   * @return request JSON with a duplicate mobile number and unique user identity fields
   */
  private String generateDuplicateMobilePayload(String phone) {
    String id = numericId();
    return """
            {
              "username": "diffuser_%s",
              "password": "Strong#Abc123",
              "firstName": "Test",
              "lastName": "User",
              "mobileNumber": "555%s",
              "email": "diff%s@fineract.test",
              "authenticationMode": "email",
              "active": true
            }
            """
        .formatted(id, phone, id);
  }

  /** Executes a scalar query against the test database using a single string parameter. */
  private String querySingleValue(String sql, String parameter) {
    Properties properties = new Properties();
    properties.setProperty("user", postgres.getUsername());
    properties.setProperty("password", postgres.getPassword());
    try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), properties);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          String value = resultSet.getString(1);
          return value != null ? value : "";
        }
        return "";
      }
    } catch (SQLException e) {
      throw new RuntimeException("Test database query failed: " + sql, e);
    }
  }

  /** Queries the enrollment token for a given username from the request_audit_table. */
  private String queryEnrollmentToken(String username) {
    String sql =
        "SELECT external_authorization_token FROM request_audit_table "
            + "WHERE username = ? AND request_type = 'ENROLLMENT' ORDER BY id DESC LIMIT 1";
    return querySingleValue(sql, username);
  }

  /** Confirms an enrollment token and returns the response. */
  private Response confirmEnrollment(String token) {
    return given(SelfServiceTestUtils.requestSpec(getFineractPort()))
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
  }

  /**
   * Executes a self-enrollment request, optionally waiting for a shared latch to coordinate
   * concurrent submissions.
   *
   * @param payload request JSON to post
   * @param latch latch used to align concurrent requests, or {@code null} for immediate execution
   * @return the HTTP response returned by the self-enrollment endpoint
   */
  private Response executeSelfEnrollment(String payload, CountDownLatch latch) {
    try {
      if (latch != null
          && !latch.await(CONCURRENT_ENROLLMENT_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail("Timed out waiting to start concurrent enrollment requests");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted while waiting to start concurrent enrollment requests", e);
    }
    return given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .body(payload)
        .when()
        .post(ENROLLMENT_PATH)
        .then()
        .log()
        .body()
        .extract()
        .response();
  }

  /**
   * Runs a scalar count query against the test database using a single string parameter.
   *
   * @param sql SQL containing a single {@code ?} placeholder
   * @param parameter value to inject into the placeholder
   * @return the parsed count returned by PostgreSQL
   */
  private long queryCount(String sql, String parameter) {
    String result = querySingleValue(sql, parameter);
    if (result != null && !result.isBlank()) {
      return Long.parseLong(result.trim());
    }
    return 0L;
  }

  /**
   * Verifies the full 2-step enrollment flow: 1. POST /client-user → 200 + disabled user created 2.
   * Query token from DB 3. User cannot login (disabled) 4. POST /client-user/confirm → 200 + user
   * enabled 5. User can login
   */
  @Test
  @DisplayName("Two-step enrollment: create disabled user, confirm token, then login")
  void testSuccessfulTwoStepEnrollment() {
    String id = numericId();
    String username = "user_" + id;
    String password = "Strong#Abc123";
    String payload =
        """
            {
              "username": "%s",
              "password": "%s",
              "firstName": "Test",
              "lastName": "User",
              "mobileNumber": "555%s",
              "email": "test%s@fineract.test",
              "authenticationMode": "email",
              "active": true
            }
            """
            .formatted(username, password, id, id);

    // Step 1: Enroll — should succeed but user is disabled
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
        "Enrollment request must succeed. Body: " + enrollResponse.body().asString());

    // User should NOT be able to login yet (disabled)
    Response disabledLogin =
        SelfServiceTestUtils.authenticate(getFineractPort(), username, password);
    assertEquals(
        401, disabledLogin.getStatusCode(), "Disabled user must not be able to authenticate");

    // Step 2: Query the enrollment token from DB
    String token = queryEnrollmentToken(username);
    assertNotNull(token, "Enrollment token must exist in request_audit_table");
    assertFalse(token.isBlank(), "Enrollment token must not be blank");

    // Step 3: Confirm enrollment — enables the user
    Response confirmResponse = confirmEnrollment(token);
    assertEquals(
        200,
        confirmResponse.getStatusCode(),
        "Enrollment confirmation must succeed. Body: " + confirmResponse.body().asString());

    // Step 4: User can now login
    Response enabledLogin =
        SelfServiceTestUtils.authenticate(getFineractPort(), username, password);
    assertEquals(
        200,
        enabledLogin.getStatusCode(),
        "Enabled user must be able to authenticate after confirmation");

    // Step 5: Token replay must be rejected
    Response replayResponse = confirmEnrollment(token);
    assertEquals(
        403,
        replayResponse.getStatusCode(),
        "Consumed enrollment token must be rejected. Body: " + replayResponse.body().asString());
  }

  /** Verifies that a second enrollment attempt with the same mobile number returns a conflict. */
  @Test
  @DisplayName("Fails enrollment due to duplicate mobile number with 409 Conflict")
  void testDuplicateMobileNumberFailsConstraint() {
    String phone = numericId();
    String id1 = numericId();
    String payload1 =
        """
            {
              "username": "user1_%s",
              "password": "Strong#Abc123",
              "firstName": "Test",
              "lastName": "User",
              "mobileNumber": "555%s",
              "email": "test1%s@fineract.test",
              "authenticationMode": "email",
              "active": true
            }
            """
            .formatted(id1, phone, id1);

    // First enroll should succeed
    Response response1 = executeSelfEnrollment(payload1, null);
    assertEquals(
        200,
        response1.getStatusCode(),
        "First enrollment should succeed. Body: " + response1.body().asString());

    String payload2 = generateDuplicateMobilePayload(phone);

    // Second enroll should fail with 409 constraint violation
    Response response2 = executeSelfEnrollment(payload2, null);
    assertEquals(
        409, response2.getStatusCode(), "Expected 409 conflict for duplicate mobile number");
  }

  /**
   * Verifies concurrent enrollments with the same mobile number do not leave orphaned client
   * records.
   */
  @Test
  @DisplayName("Concurrent enrollment avoids partial DB state via transactional isolation")
  void testConcurrentRaceCondition() {
    String phone = numericId();
    String payload1 = generateDuplicateMobilePayload(phone);
    String payload2 = generateDuplicateMobilePayload(phone);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch latch = new CountDownLatch(1);
      CompletableFuture<Response> req1 =
          CompletableFuture.supplyAsync(() -> executeSelfEnrollment(payload1, latch), executor)
              .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
      CompletableFuture<Response> req2 =
          CompletableFuture.supplyAsync(() -> executeSelfEnrollment(payload2, latch), executor)
              .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS);

      // Release the latch to start both concurrently
      latch.countDown();

      Response r1 = req1.join();
      Response r2 = req2.join();

      int status1 = r1.getStatusCode();
      int status2 = r2.getStatusCode();

      // At least one succeeds or fails, but BOTH cannot succeed
      assertTrue(
          (status1 == 200 && status2 == 409)
              || (status1 == 409 && status2 == 200)
              || (status1 == 409 && status2 == 409),
          "Expected race condition to resolve as atomic commit/fail. Got statuses: "
              + status1
              + " and "
              + status2);

      long mappedClientCount =
          queryCount(
              "select count(*) from m_client c "
                  + "join m_selfservice_user_client_mapping m on m.client_id = c.id "
                  + "where c.mobile_no = ?",
              "555" + phone);
      long orphanClientCount =
          queryCount(
              "select count(*) from m_client c "
                  + "left join m_selfservice_user_client_mapping m on m.client_id = c.id "
                  + "where c.mobile_no = ? and m.client_id is null",
              "555" + phone);

      assertEquals(
          1L,
          mappedClientCount,
          "Expected exactly one client-user mapping for the shared mobile number");
      assertEquals(0L, orphanClientCount, "Expected no orphan client for the failed enrollment");
    } finally {
      executor.shutdownNow();
    }
  }
}
