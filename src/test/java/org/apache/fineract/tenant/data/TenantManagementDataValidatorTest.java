/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantManagementDataValidatorTest {

  private final TenantManagementDataValidator validator =
      new TenantManagementDataValidator(new FromJsonHelper());

  /** A create payload with every required field present, so a test can vary one at a time. */
  private static String createJson(final String schemaName, final String identifier) {
    return
        """
        {
          "identifier": "%s",
          "name": "Acme Microfinance",
          "timezoneId": "Asia/Kolkata",
          "schemaName": "%s",
          "schemaServer": "db.example.org",
          "schemaServerPort": "5432",
          "schemaUsername": "fineract",
          "schemaPassword": "s3cret"
        }
        """
        .formatted(jsonEscape(identifier), jsonEscape(schemaName));
  }

  /**
   * Escapes a value so it survives into the JSON body intact.
   *
   * <p>Without this a hostile value containing a quote would break the payload and be rejected by
   * the JSON parser, so the test would pass without ever reaching the validator - proving nothing
   * about the rule it claims to check.
   */
  private static String jsonEscape(final String value) {
    final StringBuilder escaped = new StringBuilder();
    for (final char c : value.toCharArray()) {
      switch (c) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static List<String> parametersInError(final PlatformApiDataValidationException e) {
    return e.getErrors().stream().map(ApiParameterError::getParameterName).toList();
  }

  // ---------------------------------------------------------------
  // Schema name: concatenated into DDL, so the pattern is the defence
  // ---------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tenants; DROP TABLE tenants",
        "acme\"; DROP DATABASE x; --",
        "acme`",
        "acme'",
        "acme bar",
        "acme-bar",
        "acme.bar",
        "acme$bar",
        "acme\\bar",
        "acme/bar",
        "acme\nbar"
      })
  void create_rejectsASchemaNameThatCouldEscapeIntoDdl(final String schemaName) {
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(createJson(schemaName, "acme")));

    assertTrue(parametersInError(thrown).contains("schemaName"));
  }

  @Test
  void create_rejectsASchemaNameLongerThanThePortableIdentifierLimit() {
    // 63 is PostgreSQL's limit and the shortest across supported engines, so a
    // longer name would be creatable on one database and not another.
    final String tooLong = "a".repeat(64);

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(createJson(tooLong, "acme")));

    assertTrue(parametersInError(thrown).contains("schemaName"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"mifostenant_acme", "ACME", "a", "a_1"})
  void create_acceptsAPlainSchemaName(final String schemaName) {
    assertEquals(
        schemaName.toLowerCase(Locale.ROOT),
        validator.validateForCreate(createJson(schemaName, "acme")).schemaName());
  }

  @Test
  void create_acceptsASchemaNameAtExactlyThePortableIdentifierLimit() {
    // Boundary: 63 is allowed, 64 is not - see the test above.
    final String atLimit = "a".repeat(63);

    assertEquals(atLimit, validator.validateForCreate(createJson(atLimit, "acme")).schemaName());
  }

  // ---------------------------------------------------------------
  // Identifier: travels in an HTTP header, matched on every request
  // ---------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {"Acme", "-acme", "_acme", "acme tenant", "acme\ttenant", "acme:1", "ácme"})
  void create_rejectsAnIdentifierThatIsNotTheNarrowShape(final String identifier) {
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(createJson("acme", identifier)));

    assertTrue(parametersInError(thrown).contains("identifier"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"acme", "acme-1", "acme_1", "default", "0acme"})
  void create_acceptsAnIdentifierInTheNarrowShape(final String identifier) {
    assertEquals(
        identifier, validator.validateForCreate(createJson("acme", identifier)).identifier());
  }

  // ---------------------------------------------------------------
  // Required fields and defaults
  // ---------------------------------------------------------------

  @Test
  void create_requiresAPasswordSoATenantIsNeverProvisionedWithAGuessableOne() {
    final String json =
        """
        {
          "identifier": "acme", "name": "Acme", "timezoneId": "Asia/Kolkata",
          "schemaName": "acme", "schemaServer": "db", "schemaServerPort": "5432",
          "schemaUsername": "fineract"
        }
        """;

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class, () -> validator.validateForCreate(json));

    assertTrue(parametersInError(thrown).contains("schemaPassword"));
  }

  @Test
  void create_defaultsAutoUpdateToTrueMatchingTheColumnDefault() {
    assertTrue(validator.validateForCreate(createJson("acme", "acme")).autoUpdate());
  }

  @Test
  void create_defaultsStatusToActive() {
    assertEquals(
        TenantStatus.ACTIVE, validator.validateForCreate(createJson("acme", "acme")).status());
  }

  @Test
  void create_rejectsAStatusThatIsNotOneOfTheThree() {
    final String json =
        createJson("acme", "acme").replace("\"name\":", "\"status\": \"DELETED\", \"name\":");

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class, () -> validator.validateForCreate(json));

    assertTrue(parametersInError(thrown).contains("status"));
  }

  @Test
  void create_rejectsATimezoneThisJvmCannotResolve() {
    final String json = createJson("acme", "acme").replace("Asia/Kolkata", "Mars/Olympus_Mons");

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class, () -> validator.validateForCreate(json));

    assertTrue(parametersInError(thrown).contains("timezoneId"));
  }

  @Test
  void create_rejectsAMalformedContactEmail() {
    final String json =
        createJson("acme", "acme")
            .replace("\"name\":", "\"contactEmail\": \"not-an-email\", \"name\":");

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class, () -> validator.validateForCreate(json));

    assertTrue(parametersInError(thrown).contains("contactEmail"));
  }

  @Test
  void create_rejectsAnAbsentBody() {
    assertThrows(InvalidJsonException.class, () -> validator.validateForCreate("  "));
  }

  // ---------------------------------------------------------------
  // Update
  // ---------------------------------------------------------------

  @Test
  void update_rejectsAnAttemptToRenameTheIdentifierRatherThanIgnoringIt() {
    // Silently dropping it would leave the caller believing the rename happened.
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForUpdate("{\"identifier\": \"renamed\"}"));

    assertTrue(parametersInError(thrown).contains("identifier"));
  }

  @Test
  void update_rejectsABodyThatWouldChangeNothing() {
    assertThrows(PlatformApiDataValidationException.class, () -> validator.validateForUpdate("{}"));
  }

  @Test
  void update_leavesOmittedFieldsNullSoTheyAreNotOverwritten() {
    final TenantUpdateRequest request = validator.validateForUpdate("{\"name\": \"Renamed\"}");

    assertEquals("Renamed", request.name());
    assertNull(request.schemaPassword());
    assertNull(request.timezoneId());
    assertNull(request.autoUpdate());
  }

  @Test
  void update_treatsAWhitespaceOnlyValueAsAbsent() {
    // Otherwise a stray space would blank a tenant's name.
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForUpdate("{\"name\": \"   \"}"));
  }

  // ---------------------------------------------------------------
  // Connection test
  // ---------------------------------------------------------------

  @Test
  void connectionTest_holdsTheSameSchemaNameRuleAsCreate() {
    // Otherwise the probe could be pointed at a target create itself would refuse.
    final String json =
        """
        {
          "schemaName": "acme; DROP TABLE tenants", "schemaServer": "db",
          "schemaServerPort": "5432", "schemaUsername": "u", "schemaPassword": "p"
        }
        """;

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForConnectionTest(json));

    assertTrue(parametersInError(thrown).contains("schemaName"));
  }

  @Test
  void connectionTest_rejectsANonNumericPort() {
    final String json =
        """
        {
          "schemaName": "acme", "schemaServer": "db", "schemaServerPort": "5432; evil",
          "schemaUsername": "u", "schemaPassword": "p"
        }
        """;

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForConnectionTest(json));

    assertTrue(parametersInError(thrown).contains("schemaServerPort"));
  }

  // ---------------------------------------------------------------
  // Review follow-ups: a schema name PostgreSQL accepts, and the port range
  // ---------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"123tenant", "9acme", "0"})
  void create_rejectsASchemaNameStartingWithADigit(final String schemaName) {
    // PostgreSQL rejects an unquoted identifier that starts with a digit:
    // CREATE DATABASE 123tenant is a syntax error, so this must fail at validation.
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(createJson(schemaName, "acme")));

    assertTrue(parametersInError(thrown).contains("schemaName"));
  }

  @Test
  void create_acceptsASchemaNameStartingWithAnUnderscore() {
    assertEquals("_acme", validator.validateForCreate(createJson("_acme", "acme")).schemaName());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "65536", "99999"})
  void create_rejectsAPortOutsideTheValidRange(final String port) {
    final String json = createJson("acme", "acme").replace("\"5432\"", "\"" + port + "\"");

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class, () -> validator.validateForCreate(json));

    assertTrue(parametersInError(thrown).contains("schemaServerPort"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "65535"})
  void create_acceptsAPortAtEitherEndOfTheRange(final String port) {
    final String json = createJson("acme", "acme").replace("\"5432\"", "\"" + port + "\"");

    assertEquals(port, validator.validateForCreate(json).schemaServerPort());
  }

  @Test
  void update_rejectsAPortOutsideTheValidRange() {
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForUpdate("{\"schemaServerPort\": \"70000\"}"));

    assertTrue(parametersInError(thrown).contains("schemaServerPort"));
  }

  @Test
  void connectionTest_rejectsAPortOutsideTheValidRange() {
    final String json =
        """
        {
          "schemaName": "acme", "schemaServer": "db", "schemaServerPort": "70000",
          "schemaUsername": "u", "schemaPassword": "p"
        }
        """;

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForConnectionTest(json));

    assertTrue(parametersInError(thrown).contains("schemaServerPort"));
  }

  // ---------------------------------------------------------------
  // Review round 2: system databases, and blank values on update
  // ---------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"postgres", "TEMPLATE1", "template0", "mysql", "information_schema"})
  void create_rejectsASystemDatabase(final String schemaName) {
    // Create reuses an existing database of the requested name, so a system database
    // would have Fineract's migrations run inside it.
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(createJson(schemaName, "acme")));

    assertTrue(parametersInError(thrown).contains("schemaName"));
  }

  @Test
  void update_refusesABlankRequiredFieldInsteadOfApplyingTheRestOfTheRequest() {
    // {"name":" ","description":"updated"} used to update the description and silently
    // skip the name.
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForUpdate("{\"name\": \" \", \"description\": \"updated\"}"));

    assertTrue(parametersInError(thrown).contains("name"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"timezoneId", "schemaServer", "schemaServerPort", "schemaUsername"})
  void update_refusesABlankValueForEveryRequiredField(final String field) {
    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForUpdate("{\"" + field + "\": \"   \"}"));

    assertTrue(parametersInError(thrown).contains(field));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"\"", "\"   \"", "null"})
  void update_clearsAnOptionalFieldSentBlankOrNull(final String value) {
    final TenantUpdateRequest request =
        validator.validateForUpdate(
            "{\"description\": "
                + value
                + ", \"contactEmail\": "
                + value
                + ", \"schemaConnectionParameters\": "
                + value
                + "}");

    assertEquals("", request.description());
    assertEquals("", request.contactEmail());
    assertEquals("", request.schemaConnectionParameters());
    assertFalse(request.isEmpty());
  }

  @Test
  void update_leavesAnOmittedOptionalFieldUnchanged() {
    final TenantUpdateRequest request = validator.validateForUpdate("{\"name\": \"Renamed\"}");

    assertNull(request.description());
    assertNull(request.contactEmail());
  }

  // ---------------------------------------------------------------
  // Review round 3: canonical schema names, blank status
  // ---------------------------------------------------------------

  @Test
  void create_lowerCasesTheSchemaNameSoPostgresAndTheConnectionAgree() {
    // PostgreSQL creates CREATE DATABASE ACME as "acme"; connecting to "ACME" then fails.
    assertEquals(
        "mifostenant_acme",
        validator.validateForCreate(createJson("MifosTenant_ACME", "acme")).schemaName());
  }

  @Test
  void connectionTest_lowerCasesTheSchemaNameToo() {
    final String json =
        """
        {
          "schemaName": "ACME", "schemaServer": "db", "schemaServerPort": "5432",
          "schemaUsername": "u", "schemaPassword": "p"
        }
        """;

    assertEquals("acme", validator.validateForConnectionTest(json).schemaName());
  }

  @Test
  void create_refusesABlankStatusInsteadOfDefaultingToActive() {
    final String json =
        createJson("acme", "acme").replace("\"name\":", "\"status\": \"  \", \"name\":");

    final PlatformApiDataValidationException thrown =
        assertThrows(
            PlatformApiDataValidationException.class, () -> validator.validateForCreate(json));

    assertTrue(parametersInError(thrown).contains("status"));
  }
}
