/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "m_selfservice_onboarding_step",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_ss_onboarding_user_step_def",
            columnNames = {"appuser_id", "step_def_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfServiceOnboardingStep {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "appuser_id", nullable = false)
  private Long appUserId;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "step_def_id", nullable = false)
  private SelfServiceOnboardingStepDef stepDef;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private OnboardingStepStatus status;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "failure_reason", length = 512)
  private String failureReason;

  @Column(name = "metadata_json", columnDefinition = "TEXT")
  private String metadataJson;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public void markInProgress(LocalDateTime now) {
    this.status = OnboardingStepStatus.IN_PROGRESS;
    if (this.startedAt == null) {
      this.startedAt = now;
    }
    this.updatedAt = now;
    this.failureReason = null;
  }

  public void markCompleted(LocalDateTime now, String metadataJson) {
    this.status = OnboardingStepStatus.COMPLETED;
    this.completedAt = now;
    this.updatedAt = now;
    this.failureReason = null;
    if (metadataJson != null) {
      this.metadataJson = metadataJson;
    }
  }

  public void markFailed(LocalDateTime now, String reason) {
    this.status = OnboardingStepStatus.FAILED;
    this.updatedAt = now;
    this.failureReason = reason;
  }

  public void markSkipped(LocalDateTime now) {
    this.status = OnboardingStepStatus.SKIPPED;
    this.completedAt = now;
    this.updatedAt = now;
  }
}