/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.stepup.data.StepUpChallengeData;
import org.apache.fineract.selfservice.stepup.data.StepUpGrantData;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChallenge;
import org.apache.fineract.selfservice.stepup.domain.StepUpChallengeRepository;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.stepup.domain.StepUpStatus;
import org.apache.fineract.selfservice.stepup.event.StepUpEvent;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.totp.TotpService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Create / verify / resend step-up challenges and consume the resulting one-time grants.
 *
 * <p>Security properties enforced here (all covered by unit tests):
 *
 * <ul>
 *   <li>the OTP is single use, expires, and locks the challenge after {@code maxAttempts} wrong
 *       guesses (cumulative across resends, serialised by a row lock);
 *   <li>resend honours a database backed cooldown and a maximum count;
 *   <li>a challenge can only be read/verified by the same user AND the same session that created
 *       it, anything else is the generic {@code stepup.invalid};
 *   <li>the grant is bound to user, session, action and payload hash and is consumed atomically;
 *   <li>creating challenges is rate limited per user (anti SMS/e-mail bombing).
 * </ul>
 *
 * <p>Nothing secret is ever logged or put in an event.
 */
@Service
@Slf4j
public class StepUpChallengeService {

  private final StepUpChallengeRepository repository;
  private final StepUpSettingsProvider settingsProvider;
  private final StepUpCrypto crypto;
  private final TotpService totpService;
  private final Map<StepUpChannel, StepUpDeliveryChannel> deliveryChannels;
  private final TransactionDateUtil transactionDateUtil;
  private final ApplicationEventPublisher eventPublisher;

  public StepUpChallengeService(
      final StepUpChallengeRepository repository,
      final StepUpSettingsProvider settingsProvider,
      final StepUpCrypto crypto,
      final TotpService totpService,
      final List<StepUpDeliveryChannel> deliveryChannelList,
      final TransactionDateUtil transactionDateUtil,
      final ApplicationEventPublisher eventPublisher) {
    this.repository = repository;
    this.settingsProvider = settingsProvider;
    this.crypto = crypto;
    this.totpService = totpService;
    this.transactionDateUtil = transactionDateUtil;
    this.eventPublisher = eventPublisher;
    this.deliveryChannels = new EnumMap<>(StepUpChannel.class);
    for (final StepUpDeliveryChannel channel : deliveryChannelList) {
      this.deliveryChannels.put(channel.channel(), channel);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------------------------

  /**
   * Issues a new challenge for {@code action} and sends the OTP over {@code channel}. Any open
   * challenge of the same user and action is superseded.
   *
   * @param payloadValues the payload the client intends to execute (bound into the grant)
   */
  @Transactional
  public StepUpChallengeData createChallenge(
      final StepUpContact contact,
      final String sessionHash,
      final StepUpAction action,
      final StepUpChannel channel,
      final Map<String, String> payloadValues,
      final Locale locale,
      final String ipAddress) {
    final StepUpSettings settings = settingsProvider.current();
    if (!settings.enabled()) {
      throw StepUpException.disabled();
    }
    if (!settings.isProtected(action)) {
      throw StepUpException.actionNotProtected(action);
    }
    final Long userId = contact.userId();
    final boolean totp = channel == StepUpChannel.TOTP;
    if (totp) {
      if (!settings.isChannelAllowed(channel) || !totpService.hasActive(userId)) {
        throw StepUpException.channelUnavailable();
      }
    } else if (!settings.isChannelAllowed(channel)
        || !contact.supports(channel)
        || onlyAuthenticatorAllowed(settings, userId)) {
      throw StepUpException.channelUnavailable();
    }
    if (!totp && !crypto.hasUsableSecret()) {
      log.error(
          "Step-up is enabled but the tenant property '{}' of '{}' is missing or shorter than {}"
              + " characters; refusing to issue challenges.",
          StepUpSettings.HMAC_SECRET_PROPERTY,
          StepUpSettings.SERVICE_NAME,
          StepUpCrypto.MIN_SECRET_LENGTH);
      throw StepUpException.misconfigured();
    }

    final LocalDateTime now = now();

    final List<StepUpChallenge> lockouts =
        repository.findActiveLockouts(userId, action, StepUpStatus.LOCKED, now);
    if (!lockouts.isEmpty()) {
      throw StepUpException.locked(secondsUntil(now, lockouts.get(0).getLockedUntil()));
    }
    if (totp) {
      final Optional<Long> authenticatorLock = totpService.lockedSeconds(userId);
      if (authenticatorLock.isPresent()) {
        throw StepUpException.locked(authenticatorLock.get());
      }
    }
    if (repository.countCreatedSince(
            userId, now.minusSeconds(settings.challengeRateWindowSeconds()))
        >= settings.challengeRateLimit()) {
      throw StepUpException.rateLimited(settings.challengeRateWindowSeconds());
    }

    final List<StepUpChallenge> open =
        repository.findByUserActionAndStatusIn(
            userId, action, List.of(StepUpStatus.PENDING, StepUpStatus.VERIFIED));
    for (final StepUpChallenge previous : open) {
      previous.supersede();
    }
    if (!open.isEmpty()) {
      repository.saveAll(open);
    }

    // The code of an authenticator app is produced by the app: nothing is generated, stored or sent.
    final String otp = totp ? null : crypto.generateOtp(settings.otpLength());
    final String salt = totp ? null : crypto.newSalt();
    final StepUpChallenge challenge =
        StepUpChallenge.issue(
            userId,
            sessionHash,
            action,
            StepUpPayloadHasher.hash(action, payloadValues),
            channel,
            totp ? null : crypto.hashOtp(otp, salt),
            salt,
            settings.maxAttempts(),
            now,
            now.plusSeconds(settings.otpTtlSeconds()),
            ipAddress);
    repository.saveAndFlush(challenge);

    if (!totp) {
      deliverAfterCommit(
          contact, channel, otp, action, settings.otpTtlSeconds(), locale, ipAddress);
    }
    publish(StepUpEvent.Type.ISSUED, challenge, null, now, ipAddress);
    return toData(challenge, contact, settings);
  }

  // ---------------------------------------------------------------------------------------------
  // Verify
  // ---------------------------------------------------------------------------------------------

  /**
   * Checks the OTP. A wrong code is persisted (attempt counted, lock applied) and still reported as
   * an exception, hence {@code noRollbackFor}.
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public StepUpGrantData verify(
      final Long challengeId,
      final String otp,
      final Long userId,
      final String sessionHash,
      final String ipAddress) {
    return verify(challengeId, otp, null, userId, sessionHash, ipAddress);
  }

  /**
   * Checks the code. On a TOTP challenge either {@code otp} (the 6 digits of the app) or {@code
   * recoveryCode} is given; a recovery code is not accepted on an SMS or e-mail challenge.
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public StepUpGrantData verify(
      final Long challengeId,
      final String otp,
      final String recoveryCode,
      final Long userId,
      final String sessionHash,
      final String ipAddress) {
    final StepUpSettings settings = settingsProvider.current();
    if (!settings.enabled()) {
      throw StepUpException.disabled();
    }
    final LocalDateTime now = now();
    final StepUpChallenge challenge =
        repository
            .findOwnedForUpdate(challengeId, userId, sessionHash)
            .orElseThrow(StepUpException::invalid);

    if (challenge.getStatus() == StepUpStatus.LOCKED) {
      if (challenge.getLockedUntil() != null && challenge.getLockedUntil().isAfter(now)) {
        throw StepUpException.locked(secondsUntil(now, challenge.getLockedUntil()));
      }
      throw StepUpException.invalid();
    }
    if (!challenge.isPending()) {
      throw StepUpException.invalid();
    }
    if (challenge.isExpired(now)) {
      challenge.markExpired();
      repository.saveAndFlush(challenge);
      publish(StepUpEvent.Type.REJECTED, challenge, "otp.expired", now, ipAddress);
      throw StepUpException.otpExpired();
    }

    // A malformed code is counted as a wrong attempt too, so format errors are not an oracle and
    // cannot be used to probe for free.
    final boolean correct;
    final String failureReason;
    if (challenge.getChannel() == StepUpChannel.TOTP) {
      // The authenticator keeps its own count of wrong codes (per user, so new challenges do not
      // reset it); when that count locks it, the request is refused without counting twice.
      final TotpService.Verification result =
          totpService.verify(userId, otp, recoveryCode, ipAddress);
      if (result.outcome() == TotpService.Outcome.LOCKED) {
        throw StepUpException.locked(result.retryAfterSeconds());
      }
      correct = result.accepted();
      failureReason = recoveryCode != null ? "recovery.mismatch" : "totp.mismatch";
    } else {
      final boolean wellFormed = otp != null && recoveryCode == null && otp.matches("\\d{4,8}");
      correct =
          wellFormed && crypto.otpMatches(otp, challenge.getOtpSalt(), challenge.getOtpHash());
      failureReason = wellFormed ? "otp.mismatch" : "otp.malformed";
    }

    if (!correct) {
      final boolean nowLocked = challenge.registerFailedAttempt(now, settings.lockoutSeconds());
      repository.saveAndFlush(challenge);
      publish(
          nowLocked ? StepUpEvent.Type.LOCKED : StepUpEvent.Type.FAILED,
          challenge,
          failureReason,
          now,
          ipAddress);
      if (nowLocked) {
        throw StepUpException.locked(settings.lockoutSeconds());
      }
      throw StepUpException.otpInvalid(challenge.attemptsRemaining());
    }

    final String grantToken = crypto.newGrantToken();
    final LocalDateTime grantExpiry = now.plusSeconds(settings.grantTtlSeconds());
    challenge.markVerified(StepUpCrypto.sha256Hex(grantToken), grantExpiry, now);
    repository.saveAndFlush(challenge);
    publish(StepUpEvent.Type.VERIFIED, challenge, null, now, ipAddress);
    return new StepUpGrantData(grantToken, challenge.getAction().name(), grantExpiry.toString());
  }

  // ---------------------------------------------------------------------------------------------
  // Resend
  // ---------------------------------------------------------------------------------------------

  /**
   * Sends a fresh OTP for a still pending challenge. The cooldown and resend count come from the
   * database; attempts are NOT reset.
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public StepUpChallengeData resend(
      final Long challengeId,
      final StepUpContact contact,
      final String sessionHash,
      final Locale locale,
      final String ipAddress) {
    final StepUpSettings settings = settingsProvider.current();
    if (!settings.enabled()) {
      throw StepUpException.disabled();
    }
    final LocalDateTime now = now();
    final StepUpChallenge challenge =
        repository
            .findOwnedForUpdate(challengeId, contact.userId(), sessionHash)
            .orElseThrow(StepUpException::invalid);

    if (!challenge.isPending()) {
      throw StepUpException.invalid();
    }
    if (challenge.getChannel() == StepUpChannel.TOTP) {
      throw StepUpException.resendUnsupported();
    }
    if (challenge.isExpired(now)) {
      challenge.markExpired();
      repository.saveAndFlush(challenge);
      publish(StepUpEvent.Type.REJECTED, challenge, "otp.expired", now, ipAddress);
      throw StepUpException.otpExpired();
    }
    if (!settings.isChannelAllowed(challenge.getChannel())
        || !contact.supports(challenge.getChannel())
        || onlyAuthenticatorAllowed(settings, contact.userId())) {
      throw StepUpException.channelUnavailable();
    }
    if (challenge.isResendCoolingDown(now, settings.resendCooldownSeconds())) {
      throw StepUpException.resendCooldown(
          secondsUntil(now, challenge.resendAvailableAt(settings.resendCooldownSeconds())));
    }
    if (challenge.getResendCount() >= settings.maxResends()) {
      throw StepUpException.resendLimitReached();
    }

    final String otp = crypto.generateOtp(settings.otpLength());
    final String salt = crypto.newSalt();
    challenge.rotateOtp(
        crypto.hashOtp(otp, salt), salt, now, now.plusSeconds(settings.otpTtlSeconds()));
    repository.saveAndFlush(challenge);

    deliverAfterCommit(
        contact,
        challenge.getChannel(),
        otp,
        challenge.getAction(),
        settings.otpTtlSeconds(),
        locale,
        ipAddress);
    publish(StepUpEvent.Type.RESENT, challenge, null, now, ipAddress);
    return toData(challenge, contact, settings);
  }

  // ---------------------------------------------------------------------------------------------
  // Consume
  // ---------------------------------------------------------------------------------------------

  /**
   * Atomically consumes a grant for the given binding. Succeeds exactly once; every mismatch is the
   * same generic {@link StepUpException#invalid()} for the caller (the precise reason goes only to
   * the internal audit event).
   *
   * <p>A refusal changes nothing (the conditional UPDATE matched no row), so it must not mark the
   * surrounding transaction rollback-only: a caller that runs the guard inside a transaction of its
   * own and lets the refusal pass up ({@code noRollbackFor}) would otherwise end with an {@code
   * UnexpectedRollbackException} instead of the 403.
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public void consumeGrant(
      final String grantToken,
      final Long userId,
      final String sessionHash,
      final StepUpAction action,
      final String payloadHash,
      final String ipAddress) {
    final LocalDateTime now = now();
    final String grantHash = StepUpCrypto.sha256Hex(grantToken);
    final int consumed =
        repository.consumeGrant(
            grantHash,
            userId,
            sessionHash,
            action,
            payloadHash,
            StepUpStatus.VERIFIED,
            StepUpStatus.CONSUMED,
            now);
    final Optional<StepUpChallenge> challenge = repository.findByGrantHash(grantHash);
    if (consumed == 1) {
      challenge.ifPresent(c -> publish(StepUpEvent.Type.CONSUMED, c, null, now, ipAddress));
      return;
    }
    final String reason =
        challenge
            .map(c -> rejectionReason(c, userId, sessionHash, action, payloadHash, now))
            .orElse("token.unknown");
    eventPublisher.publishEvent(
        new StepUpEvent(
            StepUpEvent.Type.REJECTED,
            challenge.map(StepUpChallenge::getId).orElse(null),
            userId,
            action,
            challenge.map(StepUpChallenge::getChannel).orElse(null),
            reason,
            now,
            ipAddress));
    throw StepUpException.invalid();
  }

  private static String rejectionReason(
      final StepUpChallenge c,
      final Long userId,
      final String sessionHash,
      final StepUpAction action,
      final String payloadHash,
      final LocalDateTime now) {
    if (c.getStatus() != StepUpStatus.VERIFIED) {
      return "token.not.verified." + c.getStatus().name().toLowerCase(Locale.ROOT);
    }
    if (!c.getAppUserId().equals(userId)) {
      return "user.mismatch";
    }
    if (!c.getSessionHash().equals(sessionHash)) {
      return "session.mismatch";
    }
    if (c.getAction() != action) {
      return "action.mismatch";
    }
    if (!c.getPayloadHash().equals(payloadHash)) {
      return "payload.mismatch";
    }
    if (c.getGrantExpiresAt() == null || !c.getGrantExpiresAt().isAfter(now)) {
      return "grant.expired";
    }
    return "unknown";
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code true} when SMS and e-mail codes must not be offered to this user: the tenant keeps the
   * authenticator exclusive and the user has one. Otherwise a stolen session plus a SIM swap would
   * bypass the app.
   */
  private boolean onlyAuthenticatorAllowed(final StepUpSettings settings, final Long userId) {
    return settings.totpExclusive()
        && settings.isChannelAllowed(StepUpChannel.TOTP)
        && totpService.hasActive(userId);
  }

  private LocalDateTime now() {
    return transactionDateUtil.getCurrentTenantLocalDateTime();
  }

  private static long secondsUntil(final LocalDateTime from, final LocalDateTime until) {
    if (until == null) {
      return 1L;
    }
    return Math.max(1L, Duration.between(from, until).toSeconds() + 1);
  }

  private StepUpChallengeData toData(
      final StepUpChallenge challenge, final StepUpContact contact, final StepUpSettings settings) {
    return new StepUpChallengeData(
        challenge.getId(),
        challenge.getAction().name(),
        challenge.getChannel().name(),
        contact.maskedDestination(challenge.getChannel()),
        challenge.getExpiresAt().toString(),
        challenge.attemptsRemaining(),
        challenge.resendAvailableAt(settings.resendCooldownSeconds()).toString(),
        challenge.getChannel() == StepUpChannel.TOTP
            ? 0
            : Math.max(0, settings.maxResends() - challenge.getResendCount()));
  }

  private void publish(
      final StepUpEvent.Type type,
      final StepUpChallenge challenge,
      final String reason,
      final LocalDateTime now,
      final String ipAddress) {
    eventPublisher.publishEvent(
        new StepUpEvent(
            type,
            challenge.getId(),
            challenge.getAppUserId(),
            challenge.getAction(),
            challenge.getChannel(),
            reason,
            now,
            ipAddress));
  }

  /**
   * Hands the OTP to the delivery channel once the transaction has committed, so a rolled back
   * challenge never sends a code for a challenge that does not exist. Delivery problems never fail
   * the request: the user can resend.
   */
  private void deliverAfterCommit(
      final StepUpContact contact,
      final StepUpChannel channel,
      final String otp,
      final StepUpAction action,
      final int ttlSeconds,
      final Locale locale,
      final String ipAddress) {
    final StepUpDeliveryChannel delivery = deliveryChannels.get(channel);
    final Runnable send =
        () -> {
          try {
            if (delivery == null) {
              log.error("No step-up delivery channel registered for {}", channel);
              return;
            }
            delivery.deliver(contact, otp, action, ttlSeconds, locale, ipAddress);
          } catch (RuntimeException e) {
            // Deliberately no OTP in the log.
            log.error(
                "Step-up OTP delivery failed for userId={} channel={}: {}",
                contact.userId(),
                channel,
                e.getClass().getSimpleName());
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              send.run();
            }
          });
    } else {
      send.run();
    }
  }
}
