/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.domain;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OnboardingProgressData {
  private boolean onboardingComplete;
  private int totalSteps;
  private int completedSteps;
  private int progressPercent;
  /** Next step the client should show (null if complete). */
  private OnboardingStepData currentStep;
  private List<OnboardingStepData> steps;
}