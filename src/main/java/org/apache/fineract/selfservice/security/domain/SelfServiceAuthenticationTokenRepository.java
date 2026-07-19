/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface SelfServiceAuthenticationTokenRepository
    extends JpaRepository<SelfServiceAuthenticationToken, Long> {
  Optional<SelfServiceAuthenticationToken> findByTokenAndExpiresAtAfter(
      String token, LocalDateTime now);

  void deleteByUserId(Long userId);

  @Modifying
  @Transactional
  void deleteByExpiresAtBefore(LocalDateTime now);

  // Logout
  void deleteByToken(String token);

  // Update methods to filter by Token Type
  Optional<SelfServiceAuthenticationToken> findByTokenAndTokenTypeAndExpiresAtAfter(
      String token, SelfServiceAuthenticationToken.TokenType tokenType, LocalDateTime now);
}
