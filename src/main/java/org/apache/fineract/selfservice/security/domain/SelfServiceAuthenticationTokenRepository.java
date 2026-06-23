package org.apache.fineract.selfservice.security.domain;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfServiceAuthenticationTokenRepository
    extends JpaRepository<SelfServiceAuthenticationToken, Long> {
  Optional<SelfServiceAuthenticationToken> findByTokenAndExpiresAtAfter(
      String token, LocalDateTime now);

  void deleteByUserId(Long userId);

  void deleteByExpiresAtBefore(LocalDateTime now);

  // Logout
  void deleteByToken(String token);

  // Update methods to filter by Token Type
  Optional<SelfServiceAuthenticationToken> findByTokenAndTokenTypeAndExpiresAtAfter(
      String token, SelfServiceAuthenticationToken.TokenType tokenType, LocalDateTime now);
}
