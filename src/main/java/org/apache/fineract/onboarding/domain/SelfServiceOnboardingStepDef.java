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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "m_selfservice_onboarding_step_def")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfServiceOnboardingStepDef {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "step_code", nullable = false, unique = true, length = 64)
  private String stepCode;

  @Column(name = "step_order", nullable = false)
  private int stepOrder;

  @Column(name = "label", nullable = false, length = 255)
  private String label;

  @Column(name = "description", length = 512)
  private String description;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Column(name = "is_required", nullable = false)
  private boolean required;

  @Column(name = "group_code", length = 64)
  private String groupCode;

  @Column(name = "metadata_json", columnDefinition = "TEXT")
  private String metadataJson;
}