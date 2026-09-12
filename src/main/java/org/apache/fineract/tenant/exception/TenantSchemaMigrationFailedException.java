/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * Thrown when a new tenant's schema could not be populated with the platform's tables.
 *
 * <p>The underlying Liquibase failure is attached as the cause for the server log. It is not folded
 * into the user message: migration errors quote SQL, schema names and driver internals, which
 * SOUL_GUARDRAILS keeps out of API responses.
 */
public class TenantSchemaMigrationFailedException extends AbstractPlatformDomainRuleException {

  /**
   * Held here instead of passed to {@code initCause}.
   *
   * <p>Fineract's {@code AbstractPlatformException} constructs through {@code
   * RuntimeException(String, Throwable)}, which marks the cause as already set, so a later {@code
   * initCause} throws "Can't overwrite cause" - turning a clean domain error into a 500. This holds
   * on 1.15 and 1.16 alike; it went unnoticed because every test mocked the services that throw
   * this exception, and surfaced only against a running Fineract. Overriding {@link #getCause()}
   * chains the cause without that conflict, so loggers still print "Caused by".
   */
  private final Throwable underlyingCause;

  @Override
  public Throwable getCause() {
    return underlyingCause;
  }

  public TenantSchemaMigrationFailedException(final String identifier, final Throwable cause) {
    super(
        "error.msg.tenant.schema.migration.failed",
        "The schema for tenant " + identifier + " could not be migrated",
        identifier);
    this.underlyingCause = cause;
  }
}
