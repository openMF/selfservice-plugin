package org.apache.fineract.selfservice.security.service;

import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationToken;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationTokenRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelfServiceAuthenticationTokenService {

  private final SelfServiceAuthenticationTokenRepository repository;

  // Add Record for returning both tokens
  public record TokenPair(String accessToken, String refreshToken) {}

  public SelfServiceAuthenticationTokenService(
      SelfServiceAuthenticationTokenRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public TokenPair generateTokens(Long userId, String username) {
    repository.deleteByUserId(userId); // Invalidate all existing tokens for this user

    String accessToken = UUID.randomUUID().toString();
    repository.save(
        createTokenEntity(
            userId,
            username,
            accessToken,
            SelfServiceAuthenticationToken.TokenType.ACCESS,
            7)); // 7 days

    String refreshToken = UUID.randomUUID().toString();
    repository.save(
        createTokenEntity(
            userId,
            username,
            refreshToken,
            SelfServiceAuthenticationToken.TokenType.REFRESH,
            30)); // 30 days

    return new TokenPair(accessToken, refreshToken);
  }

  private SelfServiceAuthenticationToken createTokenEntity(
      Long userId,
      String username,
      String token,
      SelfServiceAuthenticationToken.TokenType type,
      int validityDays) {
    SelfServiceAuthenticationToken entity = new SelfServiceAuthenticationToken();
    entity.setUserId(userId);
    entity.setUsername(username);
    entity.setToken(token);
    entity.setTokenType(type);
    entity.setCreatedAt(LocalDateTime.now());
    entity.setExpiresAt(LocalDateTime.now().plusDays(validityDays));
    return entity;
  }

  @Transactional
  public TokenPair refreshTokens(String rawRefreshToken) {
    SelfServiceAuthenticationToken refreshEntity =
        repository
            .findByTokenAndTokenTypeAndExpiresAtAfter(
                rawRefreshToken,
                SelfServiceAuthenticationToken.TokenType.REFRESH,
                LocalDateTime.now())
            .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));

    repository.deleteByUserId(refreshEntity.getUserId());
    return generateTokens(refreshEntity.getUserId(), refreshEntity.getUsername());
  }

  @Transactional(readOnly = true)
  public String getUsernameForAccessToken(String token) {
    return repository
        .findByTokenAndTokenTypeAndExpiresAtAfter(
            token, SelfServiceAuthenticationToken.TokenType.ACCESS, LocalDateTime.now())
        .map(SelfServiceAuthenticationToken::getUsername)
        .orElse(null);
  }

  // Sliding expiration for backward compatibility with current frontend
  @Transactional
  public void extendAccessTokenExpiry(String token, int days) {
    repository
        .findByTokenAndTokenTypeAndExpiresAtAfter(
            token, SelfServiceAuthenticationToken.TokenType.ACCESS, LocalDateTime.now())
        .ifPresent(
            entity -> {
              entity.setExpiresAt(LocalDateTime.now().plusDays(days));
              repository.save(entity);
            });
  }

  @Transactional
  public String generateToken(Long userId, String username) {
    // Invalidate existing tokens for this user (single active session)
    repository.deleteByUserId(userId);

    String token = UUID.randomUUID().toString();
    SelfServiceAuthenticationToken entity = new SelfServiceAuthenticationToken();
    entity.setUserId(userId);
    entity.setUsername(username);
    entity.setToken(token);
    // FIX: The token_type column is NOT NULL. We must set it to avoid PersistenceException.
    entity.setTokenType(SelfServiceAuthenticationToken.TokenType.ACCESS);
    entity.setCreatedAt(LocalDateTime.now());
    entity.setExpiresAt(LocalDateTime.now().plusDays(7)); // 7 days validity
    repository.save(entity);
    return token;
  }

  @Transactional(readOnly = true)
  public String getUsernameForToken(String token) {
    return repository
        .findByTokenAndExpiresAtAfter(token, LocalDateTime.now())
        .map(SelfServiceAuthenticationToken::getUsername)
        .orElse(null);
  }

  @Transactional
  public void invalidateToken(String token) {
    if (StringUtils.isNotBlank(token)) {
      repository.deleteByToken(token);
    }
  }
}
