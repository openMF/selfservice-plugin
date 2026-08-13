/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompleteOnboardingUpToRequest {

  /** e.g. REGISTRATION_COMPLETE, KYC_FORM, IDENTITY_VERIFICATION */
  private String upToStepCode;
}