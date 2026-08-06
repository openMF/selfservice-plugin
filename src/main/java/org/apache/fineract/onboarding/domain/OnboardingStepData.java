/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OnboardingStepData {
  private String code;
  private int order;
  private String label;
  private String groupCode;
  private boolean required;
  private String status;
  private String startedAt;
  private String completedAt;
  private String failureReason;
  private String metadataJson;
}