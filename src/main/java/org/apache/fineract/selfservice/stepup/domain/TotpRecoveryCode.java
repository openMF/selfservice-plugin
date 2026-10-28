/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * One single-use recovery code of a user, kept only as a keyed hash ({@link
 * TotpCrypto#hashRecoveryCode}). It lets the user pass a step-up when the phone is lost.
 */
@Entity
@Table(name = "m_selfservice_totp_recovery_code")
public class TotpRecoveryCode extends AbstractPersistableCustom<Long> {

  @Column(name = "appuser_id", nullable = false)
  private Long appUserId;

  @Column(name = "code_hash", length = 64, nullable = false)
  private String codeHash;

  @Column(name = "used_at")
  private LocalDateTime usedAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected TotpRecoveryCode() {
    // JPA
  }

  public static TotpRecoveryCode issue(
      final Long appUserId, final String codeHash, final LocalDateTime now) {
    final TotpRecoveryCode code = new TotpRecoveryCode();
    code.appUserId = appUserId;
    code.codeHash = codeHash;
    code.createdAt = now;
    return code;
  }

  public boolean isUsed() {
    return usedAt != null;
  }

  public void markUsed(final LocalDateTime now) {
    this.usedAt = now;
  }

  public Long getAppUserId() {
    return appUserId;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public LocalDateTime getUsedAt() {
    return usedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  @Override
  public String toString() {
    return "TotpRecoveryCode[id=" + getId() + ", used=" + isUsed() + "]";
  }
}
