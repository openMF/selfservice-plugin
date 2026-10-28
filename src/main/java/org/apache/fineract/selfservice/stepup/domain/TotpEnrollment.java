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
 * The authenticator app of one self-service user (at most one: {@code appuser_id} is unique).
 *
 * <p>The secret is stored encrypted ({@code TotpCrypto}); the entity never exposes it in {@link
 * #toString()}. The state machine lives here so it can be unit-tested without a database. All times
 * are tenant-clock values supplied by the caller.
 */
@Entity
@Table(name = "m_selfservice_totp_enrollment")
public class TotpEnrollment extends AbstractPersistableCustom<Long> {

  /** {@code PENDING}: the secret was shown but no code of the app was seen yet. */
  public enum Status {
    PENDING,
    ACTIVE
  }

  @Column(name = "appuser_id", nullable = false, unique = true)
  private Long appUserId;

  @Column(name = "secret_enc", length = 255, nullable = false)
  private String secretEncrypted;

  @Column(name = "key_version", nullable = false)
  private int keyVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 10, nullable = false)
  private Status status;

  @Column(name = "failed_attempts", nullable = false)
  private int failedAttempts;

  @Column(name = "locked_until")
  private LocalDateTime lockedUntil;

  /** The last 30-second step accepted; a code of this step or an older one is never accepted. */
  @Column(name = "last_used_step")
  private Long lastUsedStep;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "confirmed_at")
  private LocalDateTime confirmedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected TotpEnrollment() {
    // JPA
  }

  public static TotpEnrollment pending(
      final Long appUserId,
      final String secretEncrypted,
      final int keyVersion,
      final LocalDateTime now) {
    final TotpEnrollment enrollment = new TotpEnrollment();
    enrollment.appUserId = appUserId;
    enrollment.secretEncrypted = secretEncrypted;
    enrollment.keyVersion = keyVersion;
    enrollment.status = Status.PENDING;
    enrollment.failedAttempts = 0;
    enrollment.createdAt = now;
    enrollment.version = 0L;
    return enrollment;
  }

  public boolean isActive() {
    return status == Status.ACTIVE;
  }

  public boolean isPending() {
    return status == Status.PENDING;
  }

  /** {@code true} while a lock-out is in force. */
  public boolean isLocked(final LocalDateTime now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /** Forgets a lock-out that has run out, so the user starts again with all attempts. */
  public void releaseExpiredLock(final LocalDateTime now) {
    if (lockedUntil != null && !lockedUntil.isAfter(now)) {
      lockedUntil = null;
      failedAttempts = 0;
    }
  }

  /**
   * Counts a wrong code. The count belongs to the user, not to a challenge, so asking for a new
   * challenge never resets it.
   *
   * @return {@code true} if this failure locked the authenticator
   */
  public boolean registerFailure(
      final LocalDateTime now, final int maxFailures, final int lockoutSeconds) {
    failedAttempts++;
    if (failedAttempts >= maxFailures) {
      lockedUntil = now.plusSeconds(lockoutSeconds);
      return true;
    }
    return false;
  }

  public int failuresRemaining(final int maxFailures) {
    return Math.max(0, maxFailures - failedAttempts);
  }

  /** A good code: the step is used up and the failure count starts again. */
  public void registerSuccess(final long step) {
    lastUsedStep = step;
    resetFailures();
  }

  /** The failure count starts again (a recovery code was accepted: no step was used). */
  public void resetFailures() {
    failedAttempts = 0;
    lockedUntil = null;
  }

  /** {@code true} when this step (or a later one) was already accepted. */
  public boolean isReplay(final long step) {
    return lastUsedStep != null && step <= lastUsedStep;
  }

  public void activate(final LocalDateTime now) {
    status = Status.ACTIVE;
    confirmedAt = now;
  }

  public Long getAppUserId() {
    return appUserId;
  }

  public String getSecretEncrypted() {
    return secretEncrypted;
  }

  public int getKeyVersion() {
    return keyVersion;
  }

  public Status getStatus() {
    return status;
  }

  public int getFailedAttempts() {
    return failedAttempts;
  }

  public LocalDateTime getLockedUntil() {
    return lockedUntil;
  }

  public Long getLastUsedStep() {
    return lastUsedStep;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getConfirmedAt() {
    return confirmedAt;
  }

  /** Never print the entity: it holds the encrypted secret. */
  @Override
  public String toString() {
    return "TotpEnrollment[id=" + getId() + ", status=" + status + "]";
  }
}
