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
