/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationToken;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationToken.TokenType;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationTokenRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.BadCredentialsException;

class SelfServiceAuthenticationTokenServiceInvalidationTest {

  private static final String REFRESH_TOKEN = "refresh-token";

  private final SelfServiceAuthenticationTokenRepository repository =
      mock(SelfServiceAuthenticationTokenRepository.class);
  private final AppSelfServiceUserRepository userRepository =
      mock(AppSelfServiceUserRepository.class);
  private final SelfServiceAuthenticationTokenService service =
      new SelfServiceAuthenticationTokenService(repository, userRepository);

  @Test
  void invalidateAllForUser_locksTheUserThenRemovesAllTokensOfThatUser() {
    service.invalidateAllForUser(7L);

    InOrder order = inOrder(userRepository, repository);
    order.verify(userRepository).findByIdForUpdate(7L);
    order.verify(repository).deleteAllOfUser(7L);
  }

  @Test
  void invalidateAllForUser_ignoresAMissingUserId() {
    service.invalidateAllForUser(null);

    verify(userRepository, never()).findByIdForUpdate(any());
    verify(repository, never()).deleteAllOfUser(any());
  }

  @Test
  void refreshTokens_locksTheUserBeforeUsingUpTheRefreshToken() {
    stubRefreshToken();
    when(userRepository.findByIdForUpdate(7L))
        .thenReturn(Optional.of(mock(AppSelfServiceUser.class)));
    when(repository.deleteValidToken(eq(REFRESH_TOKEN), eq(TokenType.REFRESH), any()))
        .thenReturn(1);

    assertNotNull(service.refreshTokens(REFRESH_TOKEN));

    InOrder order = inOrder(userRepository, repository);
    order.verify(userRepository).findByIdForUpdate(7L);
    order.verify(repository).deleteValidToken(eq(REFRESH_TOKEN), eq(TokenType.REFRESH), any());
    order.verify(repository, times(2)).save(any(SelfServiceAuthenticationToken.class));
  }

  @Test
  void refreshTokens_refusesATokenRevokedWhileWaitingForTheLock() {
    // The token was still there before the lock, but a password reset deleted it and committed
    // while this refresh waited, so using it up deletes nothing.
    stubRefreshToken();
    when(userRepository.findByIdForUpdate(7L))
        .thenReturn(Optional.of(mock(AppSelfServiceUser.class)));
    when(repository.deleteValidToken(eq(REFRESH_TOKEN), eq(TokenType.REFRESH), any()))
        .thenReturn(0);

    assertThrows(BadCredentialsException.class, () -> service.refreshTokens(REFRESH_TOKEN));

    verify(repository, never()).save(any());
  }

  @Test
  void refreshTokens_refusesAnUnknownToken() {
    when(repository.findByTokenAndTokenTypeAndExpiresAtAfter(
            eq(REFRESH_TOKEN), eq(TokenType.REFRESH), any()))
        .thenReturn(Optional.empty());

    assertThrows(BadCredentialsException.class, () -> service.refreshTokens(REFRESH_TOKEN));

    verify(userRepository, never()).findByIdForUpdate(any());
    verify(repository, never()).save(any());
  }

  private void stubRefreshToken() {
    SelfServiceAuthenticationToken token = new SelfServiceAuthenticationToken();
    token.setUserId(7L);
    token.setUsername("jdoe");
    token.setToken(REFRESH_TOKEN);
    token.setTokenType(TokenType.REFRESH);
    when(repository.findByTokenAndTokenTypeAndExpiresAtAfter(
            eq(REFRESH_TOKEN), eq(TokenType.REFRESH), any()))
        .thenReturn(Optional.of(token));
  }
}
