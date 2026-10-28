/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * A step-up (OTP) challenge for one sensitive operation of one self-service user.
 *
 * <p>The entity carries the whole state machine so it can be unit-tested without a database. No
 * secret is stored in clear text: the OTP is a salted HMAC ({@link #getOtpHash()}), the one-time
 * grant token is a SHA-256 digest ({@link #getGrantHash()}) and the session is referenced by a
 * SHA-256 digest of the access token ({@link #getSessionHash()}).
 *
 * <p>All timestamps are tenant-clock values supplied by the caller (see {@code
 * TransactionDateUtil#getCurrentTenantLocalDateTime()}).
 */
@Entity
@Table(name = "m_selfservice_stepup_challenge")
public class StepUpChallenge extends AbstractPersistableCustom<Long> {

  @Column(name = "appuser_id", nullable = false)
  private Long appUserId;

  @Column(name = "session_hash", length = 64, nullable = false)
  private String sessionHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "action_code", length = 40, nullable = false)
  private StepUpAction action;

  @Column(name = "payload_hash", length = 64, nullable = false)
  private String payloadHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", length = 10, nullable = false)
  private StepUpChannel channel;

  /** {@code null} for a TOTP challenge: the code comes from the app, nothing is stored. */
  @Column(name = "otp_hash", length = 64)
  private String otpHash;

  @Column(name = "otp_salt", length = 32)
  private String otpSalt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  private StepUpStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "resend_count", nullable = false)
  private int resendCount;

  @Column(name = "last_sent_at", nullable = false)
  private LocalDateTime lastSentAt;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "locked_until")
  private LocalDateTime lockedUntil;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @Column(name = "grant_hash", length = 64)
  private String grantHash;

  @Column(name = "grant_expires_at")
  private LocalDateTime grantExpiresAt;

  @Column(name = "consumed_at")
  private LocalDateTime consumedAt;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected StepUpChallenge() {
    // JPA
  }

  /** Creates a new {@link StepUpStatus#PENDING} challenge. */
  public static StepUpChallenge issue(
      final Long appUserId,
      final String sessionHash,
      final StepUpAction action,
      final String payloadHash,
      final StepUpChannel channel,
      final String otpHash,
      final String otpSalt,
      final int maxAttempts,
      final LocalDateTime now,
      final LocalDateTime expiresAt,
      final String ipAddress) {
    final StepUpChallenge challenge = new StepUpChallenge();
    challenge.appUserId = appUserId;
    challenge.sessionHash = sessionHash;
    challenge.action = action;
    challenge.payloadHash = payloadHash;
    challenge.channel = channel;
    challenge.otpHash = otpHash;
    challenge.otpSalt = otpSalt;
    challenge.status = StepUpStatus.PENDING;
    challenge.attempts = 0;
    challenge.maxAttempts = maxAttempts;
    challenge.resendCount = 0;
    challenge.lastSentAt = now;
    challenge.expiresAt = expiresAt;
    challenge.createdAt = now;
    challenge.ipAddress = ipAddress;
    challenge.version = 0L;
    return challenge;
  }

  // ---------------------------------------------------------------------------------------------
  // State queries
  // ---------------------------------------------------------------------------------------------

  public boolean isPending() {
    return status == StepUpStatus.PENDING;
  }

  /** {@code true} once {@code now} is at or after the OTP expiry. */
  public boolean isExpired(final LocalDateTime now) {
    return !now.isBefore(expiresAt);
  }

  public int attemptsRemaining() {
    return Math.max(0, maxAttempts - attempts);
  }

  /** Earliest instant at which {@link #rotateOtp} is allowed again. */
  public LocalDateTime resendAvailableAt(final int cooldownSeconds) {
    return lastSentAt.plusSeconds(cooldownSeconds);
  }

  public boolean isResendCoolingDown(final LocalDateTime now, final int cooldownSeconds) {
    return now.isBefore(resendAvailableAt(cooldownSeconds));
  }

  // ---------------------------------------------------------------------------------------------
  // State transitions
  // ---------------------------------------------------------------------------------------------

  /**
   * Counts a wrong OTP. Attempts are cumulative for the whole challenge, resends do not reset them,
   * so a client cannot multiply its guesses by asking for new codes.
   *
   * @return {@code true} if this failure locked the challenge
   */
  public boolean registerFailedAttempt(final LocalDateTime now, final int lockoutSeconds) {
    attempts++;
    if (attempts >= maxAttempts) {
      status = StepUpStatus.LOCKED;
      lockedUntil = now.plusSeconds(lockoutSeconds);
      return true;
    }
    return false;
  }

  /** Replaces the OTP (resend). Attempts are intentionally NOT reset. */
  public void rotateOtp(
      final String newOtpHash,
      final String newOtpSalt,
      final LocalDateTime now,
      final LocalDateTime newExpiresAt) {
    this.otpHash = newOtpHash;
    this.otpSalt = newOtpSalt;
    this.resendCount++;
    this.lastSentAt = now;
    this.expiresAt = newExpiresAt;
  }

  /** The OTP was correct: store the digest of the grant token handed to the client. */
  public void markVerified(
      final String newGrantHash, final LocalDateTime grantExpiry, final LocalDateTime now) {
    this.status = StepUpStatus.VERIFIED;
    this.grantHash = newGrantHash;
    this.grantExpiresAt = grantExpiry;
    this.verifiedAt = now;
  }

  public void markExpired() {
    this.status = StepUpStatus.EXPIRED;
  }

  public void supersede() {
    this.status = StepUpStatus.SUPERSEDED;
  }

  // ---------------------------------------------------------------------------------------------
  // Getters
  // ---------------------------------------------------------------------------------------------

  public Long getAppUserId() {
    return appUserId;
  }

  public String getSessionHash() {
    return sessionHash;
  }

  public StepUpAction getAction() {
    return action;
  }

  public String getPayloadHash() {
    return payloadHash;
  }

  public StepUpChannel getChannel() {
    return channel;
  }

  public String getOtpHash() {
    return otpHash;
  }

  public String getOtpSalt() {
    return otpSalt;
  }

  public StepUpStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public int getResendCount() {
    return resendCount;
  }

  public LocalDateTime getLastSentAt() {
    return lastSentAt;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getLockedUntil() {
    return lockedUntil;
  }

  public LocalDateTime getVerifiedAt() {
    return verifiedAt;
  }

  public String getGrantHash() {
    return grantHash;
  }

  public LocalDateTime getGrantExpiresAt() {
    return grantExpiresAt;
  }

  public LocalDateTime getConsumedAt() {
    return consumedAt;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  /** Never log the entity: it holds digests of secrets. */
  @Override
  public String toString() {
    return "StepUpChallenge[id="
        + getId()
        + ", action="
        + action
        + ", channel="
        + channel
        + ", status="
        + status
        + "]";
  }
}
