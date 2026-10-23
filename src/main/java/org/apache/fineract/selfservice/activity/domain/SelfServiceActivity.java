/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

/**
 * One line of the activity history of a self-service user (US-E12-05).
 *
 * <p>Privacy by design: the IP address is stored already masked and {@code details} is a small JSON
 * object built from a whitelist (see {@code ActivityDetails}). The timestamp is the tenant clock.
 */
@Entity
@Table(name = "m_selfservice_activity")
public class SelfServiceActivity extends AbstractPersistableCustom<Long> {

  @Column(name = "appuser_id", nullable = false)
  private Long appUserId;

  @Column(name = "client_id")
  private Long clientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "activity_type", length = 40, nullable = false)
  private ActivityType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", length = 10, nullable = false)
  private ActivityOutcome outcome;

  @Column(name = "ip_masked", length = 45)
  private String ipMasked;

  @Column(name = "details")
  private String details;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected SelfServiceActivity() {
    // for JPA
  }

  public SelfServiceActivity(
      final Long appUserId,
      final Long clientId,
      final ActivityType type,
      final ActivityOutcome outcome,
      final String ipMasked,
      final String details,
      final LocalDateTime createdAt) {
    this.appUserId = appUserId;
    this.clientId = clientId;
    this.type = type;
    this.outcome = outcome;
    this.ipMasked = ipMasked;
    this.details = details;
    this.createdAt = createdAt;
  }

  public Long getAppUserId() {
    return appUserId;
  }

  public Long getClientId() {
    return clientId;
  }

  public ActivityType getType() {
    return type;
  }

  public ActivityOutcome getOutcome() {
    return outcome;
  }

  public String getIpMasked() {
    return ipMasked;
  }

  public String getDetails() {
    return details;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
