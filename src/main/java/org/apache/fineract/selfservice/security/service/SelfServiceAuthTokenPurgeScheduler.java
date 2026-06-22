package org.apache.fineract.selfservice.security.service;

import java.time.LocalDateTime;
import org.apache.fineract.selfservice.security.domain.SelfServiceAuthenticationTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SelfServiceAuthTokenPurgeScheduler {

    private final SelfServiceAuthenticationTokenRepository repository;

    public SelfServiceAuthTokenPurgeScheduler(SelfServiceAuthenticationTokenRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *") // Runs every hour
    @Transactional
    public void purgeExpiredTokens() {
        repository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}