package org.apache.fineract.selfservice.security.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelfForgotPasswordApiResourceIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final AtomicLong UNIQUE_ID_SEQUENCE = new AtomicLong(System.nanoTime());

  private static String numericId() {
    return String.format("%08d", Math.floorMod(UNIQUE_ID_SEQUENCE.incrementAndGet(), 100000000));
  }

  private void enrollUser(String username, String password, String email, String mobileNumber) {
    String payload =
        """
            {
              "username": "%s",
              "password": "%s",
              "firstName": "Reset",
              "lastName": "Target",
              "mobileNumber": "%s",
              "email": "%s",
              "authenticationMode": "email",
              "active": true
            }
            """
            .formatted(username, password, mobileNumber, email);

    // Step 1: Create client + disabled user
    Response enrollResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(payload)
            .when()
            .post("/fineract-provider/api/v1/self/registration/client-user")
            .then()
            .extract()
            .response();

    assertEquals(
        200,
        enrollResponse.getStatusCode(),
        "Enrollment must succeed before password reset testing. Body: "
            + enrollResponse.body().asString());

    // Step 2: Query enrollment token and confirm
    String enrollmentToken =
        querySingleValue(
            "select external_authorization_token from request_audit_table "
                + "where username = ? and request_type = 'ENROLLMENT' order by id desc limit 1",
            username);
    assertNotNull(enrollmentToken, "Enrollment token must exist");
    assertFalse(enrollmentToken.isBlank(), "Enrollment token must not be blank");

    Response confirmResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(
                """
                    { "externalAuthenticationToken": "%s" }
                    """
                    .formatted(enrollmentToken))
            .when()
            .post("/fineract-provider/api/v1/self/registration/client-user/confirm")
            .then()
            .extract()
            .response();

    assertEquals(
        200,
        confirmResponse.getStatusCode(),
        "Enrollment confirmation must succeed. Body: " + confirmResponse.body().asString());
  }

  private String querySingleValue(String sql, String parameter) {
    Properties properties = new Properties();
    properties.setProperty("user", postgres.getUsername());
    properties.setProperty("password", postgres.getPassword());
    try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), properties);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return "";
        }
        String value = resultSet.getString(1);
        return value != null ? value.trim() : "";
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to query test database", e);
    }
  }

  private String fineractLogTail() {
    String logs = fineract.getLogs();
    if (logs == null || logs.isBlank()) {
      return "<no fineract logs available>";
    }
    String filtered =
        logs.lines()
            .filter(
                line ->
                    line.contains("ERROR")
                        || line.contains("Exception")
                        || line.contains("Caused by:")
                        || line.contains("SelfForgotPassword")
                        || line.contains("/self/password/request"))
            .reduce((left, right) -> left + System.lineSeparator() + right)
            .orElse("");
    if (!filtered.isBlank()) {
      return filtered;
    }
    int maxLength = 8000;
    if (logs.length() <= maxLength) {
      return logs;
    }
    return logs.substring(logs.length() - maxLength);
  }

  @Test
  @DisplayName("Forgot password request and renew resets the self-service password")
  void requestAndRenewPassword_endToEnd() {
    String suffix = numericId();
    String username = "reset_user_" + suffix;
    String oldPassword = "Strong#Abc123";
    String newPassword = "Stronger#Def456";
    String email = "reset" + suffix + "@fineract.test";
    String mobile = "555" + suffix;

    enrollUser(username, oldPassword, email, mobile);

    Response requestResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(
                """
                    {
                      "username": "%s",
                      "authenticationMode": "email"
                    }
                    """
                    .formatted(username))
            .when()
            .post(SelfServiceTestUtils.SELF_PASSWORD_REQUEST_PATH)
            .then()
            .extract()
            .response();

    int requestStatus = requestResponse.getStatusCode();
    assertEquals(
        200,
        requestStatus,
        "Forgot-password request must succeed. Body: "
            + requestResponse.body().asString()
            + "\nFineract logs:\n"
            + fineractLogTail());

    String externalToken =
        querySingleValue(
            "select external_authorization_token from request_audit_table "
                + "where username = ? and request_type = 'PASSWORD_RESET' order by id desc limit 1",
            username);
    assertNotNull(externalToken);
    assertFalse(externalToken.isBlank());

    Response renewResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(
                """
                    {
                      "externalAuthenticationToken": "%s",
                      "password": "%s",
                      "repeatPassword": "%s"
                    }
                    """
                    .formatted(externalToken, newPassword, newPassword))
            .when()
            .post(SelfServiceTestUtils.SELF_PASSWORD_RENEW_PATH)
            .then()
            .extract()
            .response();

    assertEquals(200, renewResponse.getStatusCode(), "Renew must succeed");

    Response oldAuth = SelfServiceTestUtils.authenticate(getFineractPort(), username, oldPassword);
    assertEquals(401, oldAuth.getStatusCode(), "Old password must no longer authenticate");

    Response newAuth = SelfServiceTestUtils.authenticate(getFineractPort(), username, newPassword);
    assertEquals(200, newAuth.getStatusCode(), "New password must authenticate");

    Response reuseResponse =
        given(SelfServiceTestUtils.requestSpec(getFineractPort()))
            .body(
                """
                    {
                      "externalAuthenticationToken": "%s",
                      "password": "%s",
                      "repeatPassword": "%s"
                    }
                    """
                    .formatted(externalToken, newPassword, newPassword))
            .when()
            .post(SelfServiceTestUtils.SELF_PASSWORD_RENEW_PATH)
            .then()
            .extract()
            .response();

    assertEquals(
        403,
        reuseResponse.getStatusCode(),
        "Consumed password-reset token must be rejected. Body: " + reuseResponse.body().asString());
  }
}
