/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.core.service;

/**
 * Thrown when the SMTP configuration needed to send email is unavailable.
 *
 * <p>This covers two scenarios:
 *
 * <ul>
 *   <li>The Fineract core {@code c_external_service_properties} table does not exist (common on
 *       PostgreSQL deployments) <em>and</em> no Spring properties fallback is configured.
 *   <li>The Spring properties fallback is present but incomplete (missing required fields {@code
 *       host} or {@code from-email}).
 * </ul>
 *
 * <p>This is a <strong>plugin-specific</strong> exception, deliberately separate from {@link
 * PlatformEmailSendException} (Fineract core) which only accepts a {@code Throwable} constructor
 * and risks being mapped to HTTP 500 by a future global exception handler.
 */
public class SmtpConfigurationUnavailableException extends RuntimeException {

  public SmtpConfigurationUnavailableException(String message) {
    super(message);
  }

  public SmtpConfigurationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
