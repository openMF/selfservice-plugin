/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

  /**
   * The raw session (access) token of this request, if the client authenticated with a session
   * token ({@code Authorization: Basic base64(token)}). Credential based Basic auth ({@code
   * user:password}) contains a colon and has no session, so it yields {@link Optional#empty()}.
   */
  static Optional<String> sessionToken(final HttpServletRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    final String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return Optional.empty();
    }
    final String decoded;
    try {
      decoded =
          new String(
              Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (decoded.isEmpty() || decoded.indexOf(':') >= 0) {
      return Optional.empty();
    }
    return Optional.of(decoded);
  }

  /** SHA-256 of the session token: the only form in which the session is stored or compared. */
  public static Optional<String> sessionHash(final HttpServletRequest request) {
    return sessionToken(request).map(StepUpCrypto::sha256Hex);
  }

  /** The step-up grant token sent by the client, if any. */
  public static Optional<String> grantToken(final HttpServletRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    final String value = request.getHeader(STEP_UP_HEADER);
    return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value.trim());
  }

  /** Best-effort client IP for the audit trail (never used for a security decision). */
  public static String clientIp(final HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String ip = request.getHeader("X-Forwarded-For");
    if (StringUtils.isNotBlank(ip)) {
      ip = ip.split(",")[0].trim();
    } else {
      ip = request.getRemoteAddr();
    }
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
