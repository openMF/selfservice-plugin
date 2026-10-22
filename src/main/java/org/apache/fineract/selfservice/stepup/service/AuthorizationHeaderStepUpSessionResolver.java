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
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Session resolver for the current self-service authentication, where the session is a bearer-like
 * token carried in the {@code Authorization} header.
 *
 * <ul>
 *   <li>{@code Authorization: Basic base64(<token>)}: what the self-service filter accepts today
 *       (see {@code SelfServiceAuthenticationConverter}); a decoded value without a colon is a
 *       session token.
 *   <li>{@code Authorization: Bearer <token>}: the standard form an OIDC based model will use; the
 *       plugin does not accept it yet, it is recognised here so step-up does not have to change.
 *   <li>{@code Authorization: Basic base64(user:password)}: credentials, not a session. Empty.
 * </ul>
 *
 * Only the SHA-256 digest of the token is ever returned.
 */
@Component
public class AuthorizationHeaderStepUpSessionResolver implements StepUpSessionResolver {

  private static final String BASIC = "Basic ";
  private static final String BEARER = "Bearer ";

  @Override
  public Optional<String> sessionHash(final HttpServletRequest request) {
    return sessionToken(request).map(StepUpCrypto::sha256Hex);
  }

  static Optional<String> sessionToken(final HttpServletRequest request) {
    if (request == null) {
      return Optional.empty();
    }
    final String header = request.getHeader("Authorization");
    if (header == null) {
      return Optional.empty();
    }
    if (header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      final String token = header.substring(BEARER.length()).trim();
      return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
    if (header.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
      final String decoded;
      try {
        decoded =
            new String(
                Base64.getDecoder().decode(header.substring(BASIC.length()).trim()),
                StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
      if (decoded.isEmpty() || decoded.indexOf(':') >= 0) {
        return Optional.empty();
      }
      return Optional.of(decoded);
    }
    return Optional.empty();
  }
}
