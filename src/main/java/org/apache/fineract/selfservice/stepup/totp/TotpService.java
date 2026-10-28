/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.totp;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.stepup.data.TotpEnrollmentData;
import org.apache.fineract.selfservice.stepup.data.TotpRecoveryCodesData;
import org.apache.fineract.selfservice.stepup.data.TotpStatusData;
import org.apache.fineract.selfservice.stepup.domain.TotpEnrollment;
import org.apache.fineract.selfservice.stepup.domain.TotpEnrollmentRepository;
import org.apache.fineract.selfservice.stepup.domain.TotpRecoveryCode;
import org.apache.fineract.selfservice.stepup.domain.TotpRecoveryCodeRepository;
import org.apache.fineract.selfservice.stepup.event.TotpEvent;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpSettings;
import org.apache.fineract.selfservice.stepup.service.StepUpSettingsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairing, using and removing the authenticator app of a self-service user (MX-488 follow-up).
 *
 * <p>Security properties enforced here (all covered by unit tests):
 *
 * <ul>
 *   <li>the secret is encrypted at rest ({@link TotpCrypto}) and returned once, at pairing;
 *   <li>pairing needs a code of the app (so we know the user really holds the secret), and
 *       activating, re-issuing recovery codes and removing an active authenticator run the caller's
 *       step-up check <b>in the same transaction</b>: a stolen session cannot pair its own phone;
 *   <li>a code is accepted for the current 30-second step and {@code totpWindowSteps} either side,
 *       and each step only once (replay protection under a row lock);
 *   <li>wrong codes count against the user, not against a challenge, so opening new challenges
 *       never resets the count; the lock-out is per tenant;
 *   <li>a recovery code works once.
 * </ul>
 *
 * <p>Nothing secret is ever logged or put in an event.
 */
@Service
@Slf4j
public class TotpService {

  /** How a code check ended. */
  public enum Outcome {
    ACCEPTED,
    RECOVERY_CODE_ACCEPTED,
    WRONG,
    LOCKED
  }

  /**
   * Result of {@link #verify}. {@code failuresRemaining} is meaningful for {@link Outcome#WRONG},
   * {@code retryAfterSeconds} for {@link Outcome#LOCKED}.
   */
  public record Verification(Outcome outcome, int failuresRemaining, long retryAfterSeconds) {

    public boolean accepted() {
      return outcome == Outcome.ACCEPTED || outcome == Outcome.RECOVERY_CODE_ACCEPTED;
    }
  }

  private final TotpEnrollmentRepository enrollments;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final TotpCrypto crypto;
  private final StepUpSettingsProvider settingsProvider;
  private final TransactionDateUtil transactionDateUtil;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  @Autowired
  public TotpService(
      final TotpEnrollmentRepository enrollments,
      final TotpRecoveryCodeRepository recoveryCodes,
      final TotpCrypto crypto,
      final StepUpSettingsProvider settingsProvider,
      final TransactionDateUtil transactionDateUtil,
      final ApplicationEventPublisher eventPublisher) {
    this(
        enrollments,
        recoveryCodes,
        crypto,
        settingsProvider,
        transactionDateUtil,
        eventPublisher,
        Clock.systemUTC());
  }

  /** A fixed clock, for tests: the TOTP step is Unix time, never the tenant's local date. */
  TotpService(
      final TotpEnrollmentRepository enrollments,
      final TotpRecoveryCodeRepository recoveryCodes,
      final TotpCrypto crypto,
      final StepUpSettingsProvider settingsProvider,
      final TransactionDateUtil transactionDateUtil,
      final ApplicationEventPublisher eventPublisher,
      final Clock clock) {
    this.enrollments = enrollments;
    this.recoveryCodes = recoveryCodes;
    this.crypto = crypto;
    this.settingsProvider = settingsProvider;
    this.transactionDateUtil = transactionDateUtil;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Questions
  // ---------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public TotpStatusData status(final Long userId) {
    final StepUpSettings settings = settingsProvider.current();
    final boolean available = settings.totpAvailable() && crypto.hasUsableKey();
    final Optional<TotpEnrollment> enrollment = enrollments.findByAppUserId(userId);
    if (enrollment.isEmpty()) {
      return new TotpStatusData(available, "NONE", 0, settings.totpExclusive(), null);
    }
    final TotpEnrollment e = enrollment.get();
    return new TotpStatusData(
        available,
        e.getStatus().name(),
        e.isActive() ? (int) recoveryCodes.countByAppUserIdAndUsedAtIsNull(userId) : 0,
        settings.totpExclusive(),
        e.getConfirmedAt() == null ? null : e.getConfirmedAt().toString());
  }

  /** {@code true} when the user has a confirmed authenticator. */
  @Transactional(readOnly = true)
  public boolean hasActive(final Long userId) {
    return enrollments.findByAppUserId(userId).map(TotpEnrollment::isActive).orElse(false);
  }

  /** Seconds the authenticator of the user stays locked, empty when it is not locked. */
  @Transactional(readOnly = true)
  public Optional<Long> lockedSeconds(final Long userId) {
    final LocalDateTime now = now();
    return enrollments
        .findByAppUserId(userId)
        .filter(e -> e.isLocked(now))
        .map(e -> secondsUntil(now, e.getLockedUntil()));
  }

  // ---------------------------------------------------------------------------------------------
  // Pairing
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates a pending secret. A pending secret of the same user is replaced; an active
   * authenticator must be removed first.
   */
  @Transactional
  public TotpEnrollmentData beginEnrollment(final Long userId, final String accountName) {
    final StepUpSettings settings = requireAvailable();
    final Optional<TotpEnrollment> existing = enrollments.findByAppUserIdForUpdate(userId);
    if (existing.isPresent() && existing.get().isActive()) {
      throw StepUpException.totpAlreadyEnrolled();
    }
    if (existing.isPresent()) {
      enrollments.delete(existing.get());
      enrollments.flush();
    }
    final byte[] secret = Totp.newSecret(crypto.random());
    try {
      enrollments.saveAndFlush(
          TotpEnrollment.pending(
              userId, crypto.encryptSecret(secret, userId), TotpCrypto.KEY_VERSION, now()));
    } catch (DataIntegrityViolationException e) {
      // Two requests of the same user started a pairing at the same moment (nothing existed to
      // lock) and the other one won: the unique key of the user says so.
      throw StepUpException.totpAlreadyEnrolled();
    }
    return new TotpEnrollmentData(
        Totp.toBase32(secret),
        Totp.otpauthUri(secret, settings.totpIssuer(), accountName),
        settings.totpIssuer(),
        Totp.DIGITS,
        Totp.PERIOD_SECONDS);
  }

  /**
   * Activates the pending authenticator once the user proves the app works (a valid code), and
   * hands out the first recovery codes. A wrong code is counted and persisted, hence {@code
   * noRollbackFor}.
   *
   * @param stepUpCheck runs after the code was accepted and before anything changes; it throws when
   *     the caller has no valid step-up grant for {@code MANAGE_AUTHENTICATOR}
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public TotpRecoveryCodesData confirmEnrollment(
      final Long userId, final String code, final String ipAddress, final Runnable stepUpCheck) {
    final StepUpSettings settings = requireAvailable();
    final TotpEnrollment enrollment =
        enrollments
            .findByAppUserIdForUpdate(userId)
            .orElseThrow(StepUpException::totpNoPendingEnrollment);
    if (enrollment.isActive()) {
      throw StepUpException.totpAlreadyEnrolled();
    }
    final LocalDateTime now = now();
    enrollment.releaseExpiredLock(now);
    if (enrollment.isLocked(now)) {
      throw StepUpException.locked(secondsUntil(now, enrollment.getLockedUntil()));
    }

    final long step = matchingStep(enrollment, userId, code, settings);
    if (step < 0) {
      throw failure(enrollment, userId, now, settings, ipAddress, "totp.confirm.mismatch");
    }
    stepUpCheck.run();

    enrollment.registerSuccess(step);
    enrollment.activate(now);
    enrollments.saveAndFlush(enrollment);
    final List<String> codes = issueRecoveryCodes(userId, settings, now);
    publish(TotpEvent.Type.ENROLLED, userId, null, now, ipAddress);
    return new TotpRecoveryCodesData(codes);
  }

  // ---------------------------------------------------------------------------------------------
  // Changes
  // ---------------------------------------------------------------------------------------------

  /** Replaces the recovery codes (the old ones stop working). Needs an active authenticator. */
  @Transactional(noRollbackFor = StepUpException.class)
  public TotpRecoveryCodesData regenerateRecoveryCodes(
      final Long userId, final String ipAddress, final Runnable stepUpCheck) {
    final StepUpSettings settings = requireAvailable();
    enrollments
        .findByAppUserIdForUpdate(userId)
        .filter(TotpEnrollment::isActive)
        .orElseThrow(StepUpException::totpNotEnrolled);
    stepUpCheck.run();
    final LocalDateTime now = now();
    final List<String> codes = issueRecoveryCodes(userId, settings, now);
    publish(TotpEvent.Type.RECOVERY_CODES_REGENERATED, userId, null, now, ipAddress);
    return new TotpRecoveryCodesData(codes);
  }

  /**
   * Removes the authenticator and its recovery codes. Cancelling a pairing that was never confirmed
   * needs no step-up; removing an active one does.
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public void remove(final Long userId, final String ipAddress, final Runnable stepUpCheck) {
    requireAvailable();
    final TotpEnrollment enrollment =
        enrollments.findByAppUserIdForUpdate(userId).orElseThrow(StepUpException::totpNotEnrolled);
    final boolean wasActive = enrollment.isActive();
    if (wasActive) {
      stepUpCheck.run();
    }
    deleteAll(userId);
    if (wasActive) {
      publish(TotpEvent.Type.REMOVED, userId, null, now(), ipAddress);
    }
  }

  /**
   * A staff member removes the authenticator of a user who lost the phone and the recovery codes.
   * It works even when the tenant has switched the feature off, so nobody stays locked out.
   *
   * @return {@code true} when there was something to remove
   */
  @Transactional
  public boolean adminReset(final Long userId) {
    final Optional<TotpEnrollment> enrollment = enrollments.findByAppUserIdForUpdate(userId);
    if (enrollment.isEmpty()) {
      return false;
    }
    final boolean wasActive = enrollment.get().isActive();
    deleteAll(userId);
    // The request is the staff member's: the event carries no address, so none reaches the history
    // of the user. Cancelling a pairing that was never confirmed is not worth a history entry.
    if (wasActive) {
      publish(TotpEvent.Type.RESET_BY_STAFF, userId, null, now(), null);
    }
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Verification of a step-up challenge
  // ---------------------------------------------------------------------------------------------

  /**
   * Checks the code (or the recovery code) of a user for a step-up challenge. Failures are
   * persisted, hence a transaction that survives {@link StepUpException}.
   *
   * @param otp the 6 digits of the app, or {@code null}
   * @param recoveryCode a recovery code, or {@code null}; exactly one of the two is given
   */
  @Transactional(noRollbackFor = StepUpException.class)
  public Verification verify(
      final Long userId, final String otp, final String recoveryCode, final String ipAddress) {
    final StepUpSettings settings = requireAvailable();
    final TotpEnrollment enrollment =
        enrollments
            .findByAppUserIdForUpdate(userId)
            .filter(TotpEnrollment::isActive)
            .orElseThrow(StepUpException::totpNotEnrolled);
    final LocalDateTime now = now();
    enrollment.releaseExpiredLock(now);
    if (enrollment.isLocked(now)) {
      return new Verification(Outcome.LOCKED, 0, secondsUntil(now, enrollment.getLockedUntil()));
    }

    if (recoveryCode != null) {
      final String normalized = TotpCrypto.normalizeRecoveryCode(recoveryCode);
      if (normalized != null
          && recoveryCodes.consume(userId, crypto.hashRecoveryCode(normalized, userId), now) == 1) {
        enrollment.resetFailures();
        enrollments.saveAndFlush(enrollment);
        publish(TotpEvent.Type.RECOVERY_CODE_USED, userId, null, now, ipAddress);
        return new Verification(Outcome.RECOVERY_CODE_ACCEPTED, 0, 0);
      }
      return registerWrong(enrollment, userId, now, settings, ipAddress, "recovery.mismatch");
    }

    final long step = matchingStep(enrollment, userId, otp, settings);
    if (step < 0) {
      return registerWrong(enrollment, userId, now, settings, ipAddress, "totp.mismatch");
    }
    if (enrollment.isReplay(step)) {
      return registerWrong(enrollment, userId, now, settings, ipAddress, "totp.replay");
    }
    enrollment.registerSuccess(step);
    enrollments.saveAndFlush(enrollment);
    return new Verification(Outcome.ACCEPTED, 0, 0);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Step-up on, authenticator on and a usable key; otherwise nothing of this works. */
  private StepUpSettings requireAvailable() {
    final StepUpSettings settings = settingsProvider.current();
    if (!settings.totpAvailable()) {
      throw StepUpException.totpDisabled();
    }
    if (!crypto.hasUsableKey()) {
      log.error(
          "The authenticator is enabled but the tenant property '{}' of '{}' is missing or shorter"
              + " than {} characters; refusing to use it.",
          StepUpSettings.TOTP_KEY_PROPERTY,
          StepUpSettings.SERVICE_NAME,
          TotpCrypto.MIN_KEY_LENGTH);
      throw StepUpException.misconfigured();
    }
    return settings;
  }

  /** The step of the code, or {@code -1} when it is malformed or does not match. */
  private long matchingStep(
      final TotpEnrollment enrollment,
      final Long userId,
      final String code,
      final StepUpSettings settings) {
    if (code == null || !code.matches("\\d{" + Totp.DIGITS + "}")) {
      return -1L;
    }
    final byte[] secret;
    try {
      secret = crypto.decryptSecret(enrollment.getSecretEncrypted(), userId);
    } catch (IllegalArgumentException e) {
      // The key changed, or the row is damaged: not the user's fault, and never a free pass.
      log.error("The authenticator secret of userId={} cannot be decrypted.", userId);
      throw StepUpException.misconfigured();
    }
    return Totp.matchingStep(
        secret, code, Totp.stepOf(clock.instant().getEpochSecond()), settings.totpWindowSteps());
  }

  private Verification registerWrong(
      final TotpEnrollment enrollment,
      final Long userId,
      final LocalDateTime now,
      final StepUpSettings settings,
      final String ipAddress,
      final String reason) {
    final boolean locked =
        enrollment.registerFailure(now, settings.totpMaxFailures(), settings.totpLockoutSeconds());
    enrollments.saveAndFlush(enrollment);
    if (locked) {
      publish(TotpEvent.Type.LOCKED, userId, reason, now, ipAddress);
      return new Verification(Outcome.LOCKED, 0, settings.totpLockoutSeconds());
    }
    return new Verification(
        Outcome.WRONG, enrollment.failuresRemaining(settings.totpMaxFailures()), 0);
  }

  /** A wrong code while pairing: counted, persisted, reported as an exception. */
  private StepUpException failure(
      final TotpEnrollment enrollment,
      final Long userId,
      final LocalDateTime now,
      final StepUpSettings settings,
      final String ipAddress,
      final String reason) {
    final Verification result = registerWrong(enrollment, userId, now, settings, ipAddress, reason);
    return result.outcome() == Outcome.LOCKED
        ? StepUpException.locked(result.retryAfterSeconds())
        : StepUpException.otpInvalid(result.failuresRemaining());
  }

  private List<String> issueRecoveryCodes(
      final Long userId, final StepUpSettings settings, final LocalDateTime now) {
    recoveryCodes.deleteByAppUserId(userId);
    recoveryCodes.flush();
    final Set<String> fresh = new LinkedHashSet<>();
    while (fresh.size() < settings.recoveryCodeCount()) {
      fresh.add(crypto.newRecoveryCode());
    }
    final List<String> shown = new ArrayList<>(fresh.size());
    for (final String code : fresh) {
      recoveryCodes.save(
          TotpRecoveryCode.issue(userId, crypto.hashRecoveryCode(code, userId), now));
      shown.add(TotpCrypto.displayRecoveryCode(code));
    }
    recoveryCodes.flush();
    return shown;
  }

  private void deleteAll(final Long userId) {
    recoveryCodes.deleteByAppUserId(userId);
    enrollments.deleteByAppUserId(userId);
    enrollments.flush();
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

  private void publish(
      final TotpEvent.Type type,
      final Long userId,
      final String reason,
      final LocalDateTime now,
      final String ipAddress) {
    eventPublisher.publishEvent(new TotpEvent(type, userId, reason, now, ipAddress));
  }
}
