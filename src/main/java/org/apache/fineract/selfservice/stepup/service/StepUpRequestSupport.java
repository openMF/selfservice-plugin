/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Small, stateless helpers that read step-up related data from the HTTP request. Nothing here logs
 * header values.
 */
public final class StepUpRequestSupport {

  /** Header carrying the one-time grant token on protected requests. */
  public static final String STEP_UP_HEADER = "X-Step-Up-Token";

  /** Product default locale when the client sends no {@code Accept-Language}. */
  static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es-MX");

  private static final int MAX_IP_LENGTH = 45;

  private StepUpRequestSupport() {}

  /** The step-up grant token sent by the client, if any. */
  public static Optional<String> grantToken(final HttpServletRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    final String value = request.getHeader(STEP_UP_HEADER);
    return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value.trim());
  }

  /**
   * The address of the connection, for the audit trail (never used for a security decision).
   *
   * <p>It is deliberately <b>not</b> read from {@code X-Forwarded-For}: any client can send that
   * header, so a forged value would hide where a request really came from. Behind a reverse proxy
   * the operator configures the trusted proxies of the server (for example {@code
   * server.forward-headers-strategy=native}); then {@code getRemoteAddr()} is the real client.
   */
  public static String clientIp(final HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    final String ip = request.getRemoteAddr();
    if (ip == null) {
      return null;
    }
    return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
  }

  /** Locale for the OTP message: {@code Accept-Language} if present, else es-MX. */
  public static Locale locale(final HttpServletRequest request) {
    if (request == null || StringUtils.isBlank(request.getHeader("Accept-Language"))) {
      return DEFAULT_LOCALE;
    }
    return request.getLocale() != null ? request.getLocale() : DEFAULT_LOCALE;
  }
}
