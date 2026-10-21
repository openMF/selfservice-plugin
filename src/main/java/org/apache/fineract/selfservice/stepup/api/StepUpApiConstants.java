/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

import java.util.Set;

/** Request parameter names of the step-up API. */
public final class StepUpApiConstants {

  private StepUpApiConstants() {}

  public static final String RESOURCE_NAME_CHALLENGE = "stepup.challenge";
  public static final String RESOURCE_NAME_VERIFY = "stepup.verify";

  public static final String actionParamName = "action";
  public static final String channelParamName = "channel";
  public static final String payloadParamName = "payload";
  public static final String otpParamName = "otp";

  public static final Set<String> CREATE_CHALLENGE_PARAMETERS =
      Set.of(actionParamName, channelParamName, payloadParamName);
  public static final Set<String> VERIFY_PARAMETERS = Set.of(otpParamName);
}
