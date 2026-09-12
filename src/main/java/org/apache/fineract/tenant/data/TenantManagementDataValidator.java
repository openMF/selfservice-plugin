/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

import com.google.gson.JsonElement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.tenant.domain.TenantSchemaName;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.springframework.stereotype.Component;

/**
 * Validates tenant administration payloads and reduces them to a typed request.
 *
 * <p>Validation here is a security boundary, not a convenience. Two of these fields reach places
 * that cannot be parameterised or easily undone:
 *
 * <ul>
 *   <li>{@code schemaName} is interpolated into {@code CREATE SCHEMA} DDL, because no JDBC driver
 *       lets an identifier be bound as a parameter. It is therefore restricted to letters, digits
 *       and underscore, which makes injection impossible by construction rather than by escaping.
 *   <li>{@code identifier} becomes the value clients send as {@code X-Mifos-Platform-TenantId} and
 *       is matched against the registry on every request, so it is held to the same narrow shape.
 * </ul>
 *
 * <p>SOUL_GUARDRAILS forbids weakening validation for convenience; if either pattern seems
 * restrictive, that is deliberate.
 */
@Component
@RequiredArgsConstructor
public class TenantManagementDataValidator {

  private static final String RESOURCE_NAME = "tenant";

  /**
   * Lower case letters, digits, underscore and dash, starting with a letter or digit.
   *
   * <p>Anchored and deliberately narrow: the identifier travels in an HTTP header and is compared
   * against the registry on every single request, so it must not be able to carry whitespace,
   * control characters or anything that could be read differently by header parsing than by SQL.
   * Case is fixed to lower so two tenants cannot differ only by case and become ambiguous.
   */
  private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,99}$");

  /**
   * The shared schema-name contract; see {@link TenantSchemaName} for why it is shaped as it is.
   *
   * <p>Read from there rather than declared here so this validator and {@code
   * TenantProvisioningService}, which concatenates the value into DDL, cannot drift apart.
   */
  private static final Pattern SCHEMA_NAME_PATTERN = TenantSchemaName.PATTERN;

  /**
   * System databases a tenant must never be bound to, compared case-insensitively.
   *
   * <p>Creating a tenant reuses an existing database of the requested name, so without this a
   * tenant could be pointed at an engine's own databases and have Fineract's migrations run inside
   * them.
   */
  private static final Set<String> RESERVED_DATABASE_NAMES =
      Set.of(
          "postgres",
          "template0",
          "template1",
          "information_schema",
          "performance_schema",
          "mysql",
          "sys");

  /** Digits only. The port is concatenated into a JDBC URL, so it must carry nothing else. */
  private static final Pattern PORT_PATTERN = Pattern.compile("^[0-9]{1,5}$");

  /**
   * A pragmatic e-mail shape: something, an {@code @}, then a dotted domain.
   *
   * <p>Not an attempt at RFC 5322 - this address is contact metadata that is displayed, never used
   * to authenticate or to route mail, so catching obvious typos is the whole job.
   */
  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

  private final FromJsonHelper fromApiJsonHelper;

  /**
   * Validates a create payload.
   *
   * @param json request body
   * @return the validated request
   * @throws InvalidJsonException when the body is absent
   * @throws PlatformApiDataValidationException when any field is missing or malformed
   */
  public TenantCreateRequest validateForCreate(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);
    final JsonElement element = this.fromApiJsonHelper.parse(json);

    final String identifier = trimmed(extract("identifier", element));
    validator
        .reset()
        .parameter("identifier")
        .value(identifier)
        .notBlank()
        .matchesRegularExpression(IDENTIFIER_PATTERN.pattern());

    final String name = trimmed(extract("name", element));
    validator.reset().parameter("name").value(name).notBlank().notExceedingLengthOf(100);

    final String timezoneId = trimmed(extract("timezoneId", element));
    validator.reset().parameter("timezoneId").value(timezoneId).notBlank();
    validateTimezone(validator, timezoneId);

    final String schemaName = lowerCased(trimmed(extract("schemaName", element)));
    validator
        .reset()
        .parameter("schemaName")
        .value(schemaName)
        .notBlank()
        .matchesRegularExpression(SCHEMA_NAME_PATTERN.pattern());
    if (schemaName != null
        && RESERVED_DATABASE_NAMES.contains(schemaName.toLowerCase(Locale.ROOT))) {
      validator
          .reset()
          .parameter("schemaName")
          .value(schemaName)
          .failWithCode("is.a.reserved.database");
    }

    final String schemaServer = trimmed(extract("schemaServer", element));
    validator
        .reset()
        .parameter("schemaServer")
        .value(schemaServer)
        .notBlank()
        .notExceedingLengthOf(100);

    final String schemaServerPort = trimmed(extract("schemaServerPort", element));
    validator
        .reset()
        .parameter("schemaServerPort")
        .value(schemaServerPort)
        .notBlank()
        .matchesRegularExpression(PORT_PATTERN.pattern());
    validatePortRange(validator, schemaServerPort);

    final String schemaUsername = trimmed(extract("schemaUsername", element));
    validator
        .reset()
        .parameter("schemaUsername")
        .value(schemaUsername)
        .notBlank()
        .notExceedingLengthOf(100);

    // Required on create and never defaulted: a tenant silently provisioned with a
    // guessable password would be reachable by anyone who guessed it.
    final String schemaPassword = extract("schemaPassword", element);
    validator.reset().parameter("schemaPassword").value(schemaPassword).notBlank();

    final String description = trimmed(extract("description", element));
    validator
        .reset()
        .parameter("description")
        .value(description)
        .ignoreIfNull()
        .notExceedingLengthOf(500);

    final String contactEmail = trimmed(extract("contactEmail", element));
    validateEmail(validator, contactEmail);

    final TenantStatus status = resolveStatus(validator, element, TenantStatus.ACTIVE);

    final String connectionParameters = trimmed(extract("schemaConnectionParameters", element));
    final Boolean autoUpdate = this.fromApiJsonHelper.extractBooleanNamed("autoUpdate", element);

    throwIfErrors(errors);

    return new TenantCreateRequest(
        identifier,
        name,
        timezoneId,
        status,
        description,
        contactEmail,
        schemaName,
        schemaServer,
        schemaServerPort,
        schemaUsername,
        schemaPassword,
        connectionParameters,
        // Defaults to true, matching the tenant_server_connections column default, so a
        // tenant created through the API is migrated on startup like every existing one.
        autoUpdate == null || autoUpdate);
  }

  /**
   * Validates an update payload. Absent fields mean "leave unchanged".
   *
   * @param json request body
   * @return the validated request
   * @throws InvalidJsonException when the body is absent
   * @throws PlatformApiDataValidationException when a supplied field is malformed, or the body
   *     would change nothing
   */
  public TenantUpdateRequest validateForUpdate(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);
    final JsonElement element = this.fromApiJsonHelper.parse(json);

    // Rejected rather than ignored. Silently dropping an identifier the caller believed
    // it was changing is worse than refusing: the caller would carry on assuming the
    // rename took effect.
    if (this.fromApiJsonHelper.parameterExists("identifier", element)) {
      validator.reset().parameter("identifier").value(null).failWithCode("cannot.be.changed");
    }

    final String name = requiredWhenPresent(validator, element, "name");
    validator.reset().parameter("name").value(name).ignoreIfNull().notExceedingLengthOf(100);

    final String timezoneId = requiredWhenPresent(validator, element, "timezoneId");
    validateTimezone(validator, timezoneId);

    final String description = clearableWhenPresent(element, "description");
    validator
        .reset()
        .parameter("description")
        .value(description)
        .ignoreIfNull()
        .notExceedingLengthOf(500);

    final String contactEmail = clearableWhenPresent(element, "contactEmail");
    validateEmail(validator, contactEmail);

    final String schemaServer = requiredWhenPresent(validator, element, "schemaServer");
    validator
        .reset()
        .parameter("schemaServer")
        .value(schemaServer)
        .ignoreIfNull()
        .notExceedingLengthOf(100);

    final String schemaServerPort = requiredWhenPresent(validator, element, "schemaServerPort");
    if (schemaServerPort != null) {
      validator
          .reset()
          .parameter("schemaServerPort")
          .value(schemaServerPort)
          .matchesRegularExpression(PORT_PATTERN.pattern());
      validatePortRange(validator, schemaServerPort);
    }

    final String schemaUsername = requiredWhenPresent(validator, element, "schemaUsername");
    validator
        .reset()
        .parameter("schemaUsername")
        .value(schemaUsername)
        .ignoreIfNull()
        .notExceedingLengthOf(100);

    final String schemaPassword = extract("schemaPassword", element);
    if (schemaPassword != null) {
      validator.reset().parameter("schemaPassword").value(schemaPassword).notBlank();
    }

    final String connectionParameters = clearableWhenPresent(element, "schemaConnectionParameters");
    final Boolean autoUpdate = this.fromApiJsonHelper.extractBooleanNamed("autoUpdate", element);

    final TenantUpdateRequest request =
        new TenantUpdateRequest(
            name,
            timezoneId,
            description,
            contactEmail,
            schemaServer,
            schemaServerPort,
            schemaUsername,
            schemaPassword,
            connectionParameters,
            autoUpdate);

    if (errors.isEmpty() && request.isEmpty()) {
      validator.reset().parameter("id").value(null).failWithCode("no.parameters.for.update");
    }

    throwIfErrors(errors);
    return request;
  }

  /**
   * Validates a connection-test payload.
   *
   * <p>Holds the same shape rules as create - in particular the schema name pattern - so a probe
   * cannot be used to reach a target that create itself would refuse.
   *
   * @param json request body
   * @return the validated request
   * @throws InvalidJsonException when the body is absent
   * @throws PlatformApiDataValidationException when any field is missing or malformed
   */
  public TenantConnectionTestRequest validateForConnectionTest(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);
    final JsonElement element = this.fromApiJsonHelper.parse(json);

    final String schemaName = lowerCased(trimmed(extract("schemaName", element)));
    validator
        .reset()
        .parameter("schemaName")
        .value(schemaName)
        .notBlank()
        .matchesRegularExpression(SCHEMA_NAME_PATTERN.pattern());

    final String schemaServer = trimmed(extract("schemaServer", element));
    validator
        .reset()
        .parameter("schemaServer")
        .value(schemaServer)
        .notBlank()
        .notExceedingLengthOf(100);

    final String schemaServerPort = trimmed(extract("schemaServerPort", element));
    validator
        .reset()
        .parameter("schemaServerPort")
        .value(schemaServerPort)
        .notBlank()
        .matchesRegularExpression(PORT_PATTERN.pattern());
    validatePortRange(validator, schemaServerPort);

    final String schemaUsername = trimmed(extract("schemaUsername", element));
    validator
        .reset()
        .parameter("schemaUsername")
        .value(schemaUsername)
        .notBlank()
        .notExceedingLengthOf(100);

    final String schemaPassword = extract("schemaPassword", element);
    validator.reset().parameter("schemaPassword").value(schemaPassword).notBlank();

    final String connectionParameters = trimmed(extract("schemaConnectionParameters", element));

    throwIfErrors(errors);

    return new TenantConnectionTestRequest(
        schemaServer,
        schemaServerPort,
        schemaName,
        schemaUsername,
        schemaPassword,
        connectionParameters);
  }

  /**
   * Reads a field an update may omit but, when it is sent, must carry a value.
   *
   * <p>A blank value is refused rather than read as "leave unchanged": a caller who sent {@code
   * "name": " "} meant to change the name, and quietly applying the rest of the request would hide
   * that it did not happen.
   */
  private String requiredWhenPresent(
      final DataValidatorBuilder validator, final JsonElement element, final String parameterName) {
    final String value = trimmed(extract(parameterName, element));
    if (value == null && this.fromApiJsonHelper.parameterExists(parameterName, element)) {
      validator.reset().parameter(parameterName).value(null).failWithCode("cannot.be.blank");
    }
    return value;
  }

  /**
   * Reads an optional field an update may clear.
   *
   * @return null when the field is omitted (leave unchanged), an empty string when it is sent blank
   *     or null (clear it), otherwise the trimmed value
   */
  private String clearableWhenPresent(final JsonElement element, final String parameterName) {
    final String value = trimmed(extract(parameterName, element));
    if (value == null && this.fromApiJsonHelper.parameterExists(parameterName, element)) {
      return "";
    }
    return value;
  }

  /**
   * Rejects a port outside 1-65535.
   *
   * <p>The digit pattern alone admits 65536-99999, which would pass validation and then fail as an
   * unreachable database - reported as a connection problem instead of the input mistake it is.
   * Blank and non-numeric values are left to the checks that already report them.
   */
  private static void validatePortRange(final DataValidatorBuilder validator, final String port) {
    if (port == null || !PORT_PATTERN.matcher(port).matches()) {
      return;
    }
    final int value = Integer.parseInt(port);
    if (value < 1 || value > 65535) {
      validator
          .reset()
          .parameter("schemaServerPort")
          .value(port)
          .failWithCode("is.not.a.valid.port", 1, 65535);
    }
  }

  /**
   * Resolves a {@code status} field.
   *
   * <p>Reported through {@link DataValidatorBuilder#failWithCode} rather than a chain of rules
   * because "is one of these three names" is a single decision; {@link TenantStatus#fromString}
   * already owns it, and duplicating the list here would let the two drift.
   */
  private TenantStatus resolveStatus(
      final DataValidatorBuilder validator,
      final JsonElement element,
      final TenantStatus fallback) {
    final String status = trimmed(extract("status", element));
    if (status == null) {
      // A status sent blank is malformed input, not an omission: defaulting it to ACTIVE
      // would create a live tenant the caller never asked for.
      if (this.fromApiJsonHelper.parameterExists("status", element)) {
        validator.reset().parameter("status").value(null).failWithCode("cannot.be.blank");
      }
      return fallback;
    }
    return TenantStatus.fromString(status)
        .orElseGet(
            () -> {
              validator
                  .reset()
                  .parameter("status")
                  .value(status)
                  .failWithCode(
                      "is.not.a.supported.status", String.join(", ", TenantStatus.names()));
              return fallback;
            });
  }

  /** Checks a zone against this JVM, which is what will ultimately resolve it at runtime. */
  private void validateTimezone(final DataValidatorBuilder validator, final String timezoneId) {
    if (timezoneId == null || timezoneId.isBlank()) {
      return;
    }
    if (!ZoneId.getAvailableZoneIds().contains(timezoneId)) {
      validator
          .reset()
          .parameter("timezoneId")
          .value(timezoneId)
          .failWithCode("is.not.a.known.timezone");
    }
  }

  private void validateEmail(final DataValidatorBuilder validator, final String contactEmail) {
    if (contactEmail == null) {
      return;
    }
    validator.reset().parameter("contactEmail").value(contactEmail).notExceedingLengthOf(150);
    if (!contactEmail.isBlank() && !EMAIL_PATTERN.matcher(contactEmail).matches()) {
      validator
          .reset()
          .parameter("contactEmail")
          .value(contactEmail)
          .failWithCode("is.not.a.valid.email");
    }
  }

  /**
   * Reads a string field.
   *
   * <p>Goes through {@link FromJsonHelper} rather than {@code JsonCommand.from(String)}: that
   * factory leaves the command's own helper null, so reading a parameter off it throws.
   */
  private String extract(final String parameterName, final JsonElement element) {
    return this.fromApiJsonHelper.extractStringNamed(parameterName, element);
  }

  /**
   * Canonicalises a schema name to lower case.
   *
   * <p>PostgreSQL folds an unquoted {@code CREATE DATABASE ACME} to {@code acme}, while the
   * existence check and the JDBC URL use the name exactly as given. A mixed-case name therefore
   * created a database the platform could then neither find nor connect to, and left it orphaned.
   * One lower-case value is used everywhere instead. Pinned to {@link Locale#ROOT} so a Turkish
   * locale cannot fold {@code I} to a dotless {@code ı}.
   */
  private static String lowerCased(final String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  /** Trims, mapping a value that was only whitespace to null so it is treated as absent. */
  private static String trimmed(final String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * @return the identifier lower-cased for comparison, pinned to {@link Locale#ROOT} so a Turkish
   *     locale cannot fold {@code I} to a dotless {@code ı} and change what matches
   */
  public static String normaliseIdentifier(final String identifier) {
    return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
  }

  private static void throwIfErrors(final List<ApiParameterError> errors) {
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }
}
