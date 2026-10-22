/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationTokenRepository;
import org.junit.jupiter.api.Test;

class SelfServiceAuthenticationTokenServiceInvalidationTest {

  private final SelfServiceAuthenticationTokenRepository repository =
      mock(SelfServiceAuthenticationTokenRepository.class);
  private final SelfServiceAuthenticationTokenService service =
      new SelfServiceAuthenticationTokenService(repository);

  @Test
  void invalidateAllForUser_removesAccessAndRefreshTokensOfThatUser() {
    service.invalidateAllForUser(7L);

    verify(repository).deleteByUserId(7L);
  }

  @Test
  void invalidateAllForUser_ignoresAMissingUserId() {
    service.invalidateAllForUser(null);

    verify(repository, never()).deleteByUserId(org.mockito.ArgumentMatchers.any());
  }
}
