package org.apache.fineract.selfservice.security.service;

import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationToken;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.commons.lang3.StringUtils;

@Service
public class SelfServiceAuthenticationTokenService {

    private final SelfServiceAuthenticationTokenRepository repository;

    public SelfServiceAuthenticationTokenService(SelfServiceAuthenticationTokenRepository repository) {
        this.repository = repository;
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
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plusDays(7)); // 7 days validity
        repository.save(entity);
        return token;
    }

    @Transactional(readOnly = true)
    public String getUsernameForToken(String token) {
        return repository.findByTokenAndExpiresAtAfter(token, LocalDateTime.now())
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