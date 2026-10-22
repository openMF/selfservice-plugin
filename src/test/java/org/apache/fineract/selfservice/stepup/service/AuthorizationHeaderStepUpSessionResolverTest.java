/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthorizationHeaderStepUpSessionResolverTest {

  private static final String TOKEN = "11111111-2222-3333-4444-555555555555";
  private final AuthorizationHeaderStepUpSessionResolver resolver =
      new AuthorizationHeaderStepUpSessionResolver();

  private static String basic(final String raw) {
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static HttpServletRequest withAuthorization(final String value) {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn(value);
    return request;
  }

  @Test
  void sessionTokenWrappedInBasic_isTheCurrentFormat() {
    assertEquals(
        Optional.of(StepUpCrypto.sha256Hex(TOKEN)),
        resolver.sessionHash(withAuthorization(basic(TOKEN))));
  }

  @Test
  void theSchemeNameIsCaseInsensitive() {
    assertEquals(
        Optional.of(StepUpCrypto.sha256Hex(TOKEN)),
        resolver.sessionHash(withAuthorization("basic " + basic(TOKEN).substring(6))));
    assertEquals(
        Optional.of(StepUpCrypto.sha256Hex(TOKEN)),
        resolver.sessionHash(withAuthorization("BEARER " + TOKEN)));
  }

  @Test
  void bearerToken_isRecognisedForTheOidcBasedModel() {
    assertEquals(
        Optional.of(StepUpCrypto.sha256Hex(TOKEN)),
        resolver.sessionHash(withAuthorization("Bearer " + TOKEN)));
    // an OIDC access token is a JWT (dots, no colon)
    final String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";
    assertEquals(
        Optional.of(StepUpCrypto.sha256Hex(jwt)),
        resolver.sessionHash(withAuthorization("Bearer " + jwt)));
  }

  @Test
  void theSameTokenGivesTheSameSession_whicheverSchemeCarriesIt() {
    assertEquals(
        resolver.sessionHash(withAuthorization(basic(TOKEN))),
        resolver.sessionHash(withAuthorization("Bearer " + TOKEN)));
  }

  @Test
  void differentTokens_giveDifferentSessions() {
    assertNotEquals(
        resolver.sessionHash(withAuthorization("Bearer " + TOKEN)),
        resolver.sessionHash(withAuthorization("Bearer " + TOKEN + "x")));
  }

  @Test
  void credentialBasedBasic_hasNoSession() {
    assertTrue(resolver.sessionHash(withAuthorization(basic("ana:secret"))).isEmpty());
    assertTrue(resolver.sessionHash(withAuthorization(basic("ana:"))).isEmpty());
  }

  @Test
  void malformedOrMissingHeaders_haveNoSession() {
    assertTrue(resolver.sessionHash(withAuthorization(null)).isEmpty());
    assertTrue(resolver.sessionHash(withAuthorization("")).isEmpty());
    assertTrue(resolver.sessionHash(withAuthorization("Basic !!!not-base64!!!")).isEmpty());
    assertTrue(resolver.sessionHash(withAuthorization("Basic ")).isEmpty());
    assertTrue(resolver.sessionHash(withAuthorization("Bearer ")).isEmpty());
    assertTrue(resolver.sessionHash(withAuthorization("Digest abc")).isEmpty());
    assertTrue(resolver.sessionHash(null).isEmpty());
  }

  @Test
  void onlyADigestIsReturned_neverTheRawToken() {
    final String hash = resolver.sessionHash(withAuthorization("Bearer " + TOKEN)).orElseThrow();
    assertEquals(64, hash.length());
    assertTrue(!hash.contains(TOKEN));
  }
}
