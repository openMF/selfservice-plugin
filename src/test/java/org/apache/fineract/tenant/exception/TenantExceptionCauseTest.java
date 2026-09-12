/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TenantExceptionCauseTest {

  @Test
  void aConnectionFailureCarriesTheDriverErrorAsItsCause() {
    // Constructing must not throw. The previous initCause-based version threw
    // "Can't overwrite cause" on every Fineract version, so a wrong password became a 500.
    final SQLException driverError = new SQLException("password authentication failed");

    final TenantConnectionFailedException e =
        new TenantConnectionFailedException("db", "5432", "acme", driverError);

    assertSame(driverError, e.getCause());
  }

  @Test
  void aMigrationFailureCarriesTheLiquibaseErrorAsItsCause() {
    final IllegalStateException liquibaseError = new IllegalStateException("changeset failed");

    final TenantSchemaMigrationFailedException e =
        new TenantSchemaMigrationFailedException("acme", liquibaseError);

    assertSame(liquibaseError, e.getCause());
  }

  @Test
  void theDriverErrorStaysOutOfTheUserFacingMessage() {
    // Driver messages echo users and connection details; they belong in the log only.
    final TenantConnectionFailedException e =
        new TenantConnectionFailedException(
            "db",
            "5432",
            "acme",
            new SQLException("FATAL: password authentication failed for user \"postgres\""));

    assertFalse(e.getMessage().contains("password authentication"));
  }
}
