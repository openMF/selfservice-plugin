/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** OpenAPI schemas for {@link SelfStepUpApiResource} (documentation only). */
final class StepUpApiResourceSwagger {

  private StepUpApiResourceSwagger() {}

  @Schema(description = "PostStepUpChallengeRequest")
  static final class PostStepUpChallengeRequest {

    private PostStepUpChallengeRequest() {}

    @Schema(
        example = "TPT_TRANSFER",
        allowableValues = {"TPT_TRANSFER", "OWN_TRANSFER", "CONFIRM_TRANSFER", "CHANGE_PASSWORD"})
    public String action;

    @Schema(
        example = "SMS",
        allowableValues = {"SMS", "EMAIL"})
    public String channel;

    @Schema(
        description =
            "The data of the operation the user is about to confirm (the same fields the protected"
                + " request will carry). The resulting token only works for exactly this payload."
                + " Not needed for CHANGE_PASSWORD.",
        example =
            "{\"fromAccountId\":1,\"fromAccountType\":2,\"toAccountId\":2,\"toAccountType\":2,"
                + "\"transferAmount\":100}")
    public Map<String, Object> payload;
  }

  @Schema(description = "PostStepUpVerifyRequest")
  static final class PostStepUpVerifyRequest {

    private PostStepUpVerifyRequest() {}

    @Schema(example = "123456", description = "The one-time code received by SMS or e-mail.")
    public String otp;
  }

  @Schema(description = "StepUpChallengeResponse")
  static final class StepUpChallengeResponse {

    private StepUpChallengeResponse() {}

    public Long challengeId;
    public String action;
    public String channel;

    @Schema(example = "******0324")
    public String maskedDestination;

    @Schema(example = "2026-10-21T12:05:00")
    public String expiresAt;

    public Integer attemptsRemaining;
    public String resendAvailableAt;
    public Integer resendsRemaining;
  }

  @Schema(description = "StepUpGrantResponse")
  static final class StepUpGrantResponse {

    private StepUpGrantResponse() {}

    @Schema(
        description = "Send as the X-Step-Up-Token header on the protected request. Works once.")
    public String stepUpToken;

    public String action;
    public String expiresAt;
  }

  @Schema(description = "StepUpConfigResponse")
  static final class StepUpConfigResponse {

    private StepUpConfigResponse() {}

    public Boolean enabled;
    public List<String> protectedActions;
    public List<String> availableChannels;
    public Integer otpLength;
    public Integer otpTtlSeconds;
    public Integer maxAttempts;
    public Integer resendCooldownSeconds;
    public Integer maxResends;
    public Integer grantTtlSeconds;

    @Schema(
        description =
            "false when the caller authenticated with user:password instead of a session token;"
                + " step-up cannot be used in that case.")
    public Boolean sessionBound;
  }
}
