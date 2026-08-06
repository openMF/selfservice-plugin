/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateOnboardingStepRequest {
  /** OnboardingStepCode name, e.g. DOCUMENT_UPLOAD */
  private String stepCode;
  /** PENDING | IN_PROGRESS | COMPLETED | SKIPPED | FAILED */
  private String status;
  private String failureReason;
  /** Optional JSON string */
  private String metadataJson;
}