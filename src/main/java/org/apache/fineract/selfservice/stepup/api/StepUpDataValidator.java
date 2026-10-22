/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.stepup.service.StepUpPayloadHasher;
import org.springframework.stereotype.Component;

/** Validates the JSON bodies of the step-up API (Fineract {@code DataValidatorBuilder} style). */
@Component
@RequiredArgsConstructor
public class StepUpDataValidator {

  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
  private static final int MAX_OTP_INPUT_LENGTH = 16;

  private final FromJsonHelper fromApiJsonHelper;

  /** A validated "create challenge" request. */
  public record CreateChallengeRequest(
      StepUpAction action, StepUpChannel channel, Map<String, String> payloadValues) {}

  public CreateChallengeRequest validateCreateChallenge(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }
    fromApiJsonHelper.checkForUnsupportedParameters(
        MAP_TYPE, json, StepUpApiConstants.CREATE_CHALLENGE_PARAMETERS);
    final JsonElement element = fromApiJsonHelper.parse(json);

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator =
        new DataValidatorBuilder(errors).resource(StepUpApiConstants.RESOURCE_NAME_CHALLENGE);

    final String actionRaw =
        upperTrim(
            fromApiJsonHelper.extractStringNamed(StepUpApiConstants.actionParamName, element));
    validator
        .reset()
        .parameter(StepUpApiConstants.actionParamName)
        .value(lower(actionRaw))
        .notBlank()
        .isOneOfTheseStringValues(lowerNames(StepUpAction.values()));
    final String channelRaw =
        upperTrim(
            fromApiJsonHelper.extractStringNamed(StepUpApiConstants.channelParamName, element));
    validator
        .reset()
        .parameter(StepUpApiConstants.channelParamName)
        .value(lower(channelRaw))
        .notBlank()
        .isOneOfTheseStringValues(lowerNames(StepUpChannel.values()));
    throwIfErrors(errors);

    final StepUpAction action = StepUpAction.valueOf(actionRaw);
    final StepUpChannel channel = StepUpChannel.valueOf(channelRaw);

    final JsonObject root = element.getAsJsonObject();
    final JsonElement payloadElement = root.get(StepUpApiConstants.payloadParamName);
    JsonObject payload = null;
    if (payloadElement != null && !payloadElement.isJsonNull()) {
      if (!payloadElement.isJsonObject()) {
        validator
            .reset()
            .parameter(StepUpApiConstants.payloadParamName)
            .failWithCode("must.be.an.object", "The payload must be a JSON object.");
        throwIfErrors(errors);
      }
      payload = payloadElement.getAsJsonObject();
    }
    final Map<String, String> values = StepUpPayloadHasher.valuesFromJson(action, payload);
    for (final String missing : StepUpPayloadHasher.missingRequiredFields(action, values)) {
      validator
          .reset()
          .parameter(StepUpApiConstants.payloadParamName + "." + missing)
          .failWithCode("required", "The payload field '" + missing + "' is required.");
    }
    throwIfErrors(errors);

    return new CreateChallengeRequest(action, channel, values);
  }

  /** Returns the raw OTP text exactly as sent; the service decides if it is well formed. */
  public String validateVerify(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }
    fromApiJsonHelper.checkForUnsupportedParameters(
        MAP_TYPE, json, StepUpApiConstants.VERIFY_PARAMETERS);
    final JsonElement element = fromApiJsonHelper.parse(json);

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator =
        new DataValidatorBuilder(errors).resource(StepUpApiConstants.RESOURCE_NAME_VERIFY);
    final String otp =
        fromApiJsonHelper.extractStringNamed(StepUpApiConstants.otpParamName, element);
    validator
        .reset()
        .parameter(StepUpApiConstants.otpParamName)
        .value(otp)
        .notBlank()
        .notExceedingLengthOf(MAX_OTP_INPUT_LENGTH);
    throwIfErrors(errors);
    return otp.trim();
  }

  private static String upperTrim(final String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  // Fineract's isOneOfTheseStringValues compares the lower-cased input with the given values.
  private static String lower(final String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  private static Object[] lowerNames(final Enum<?>[] values) {
    return Arrays.stream(values).map(v -> v.name().toLowerCase(Locale.ROOT)).toArray();
  }

  private static void throwIfErrors(final List<ApiParameterError> errors) {
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }
}
