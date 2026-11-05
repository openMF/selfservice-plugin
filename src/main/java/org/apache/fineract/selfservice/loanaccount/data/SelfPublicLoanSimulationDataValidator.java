/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.data;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

/**
 * Validates public loan simulation requests. This validator is a security gate-keeper only — it
 * does NOT re-validate loan-specific fields ({@code productId}, {@code principal}, etc.) because
 * the core {@code LoanScheduleCalculationPlatformService} handles that and returns proper 400
 * errors.
 *
 * <p>This validator enforces exactly two constraints:
 *
 * <ol>
 *   <li>{@code command} query parameter must be exactly {@code "calculateLoanSchedule"} — without
 *       this, the core method's else-branch creates a real loan via {@code
 *       CommandWrapperBuilder.createLoanApplication()}
 *   <li>Request body must NOT contain {@code clientId} — anonymous users cannot associate
 *       simulations with real clients
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SelfPublicLoanSimulationDataValidator {

  private static final String CALCULATE_LOAN_SCHEDULE = "calculateLoanSchedule";

  private final FromJsonHelper fromJsonHelper;

  /**
   * Validates a public simulation request.
   *
   * @param commandParam the {@code command} query parameter value
   * @param json the request body JSON
   * @throws UnrecognizedQueryParamException if {@code command} is not {@code
   *     "calculateLoanSchedule"}
   * @throws InvalidJsonException if the request body is blank, malformed or not a JSON object
   * @throws PlatformApiDataValidationException if the request body contains {@code clientId}
   */
  public void validatePublicSimulationRequest(final String commandParam, final String json) {
    validateCommand(commandParam);
    validateRequestBody(json);
  }

  private void validateCommand(final String commandParam) {
    if (!CALCULATE_LOAN_SCHEDULE.equals(commandParam)) {
      throw new UnrecognizedQueryParamException("command", commandParam, CALCULATE_LOAN_SCHEDULE);
    }
  }

  /**
   * Validates the request body JSON structure. Rejects blank/null JSON and bodies containing {@code
   * clientId}. Malformed JSON ({@code JsonSyntaxException}) is translated to {@link
   * InvalidJsonException} for consistent 400 error responses.
   */
  private void validateRequestBody(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final com.google.gson.JsonElement element;
    try {
      element = this.fromJsonHelper.parse(json);
    } catch (com.google.gson.JsonSyntaxException e) {
      throw new InvalidJsonException();
    }
    // Valid JSON that isn't an object (e.g. [], "x", 123) would make parameterExists() throw
    // IllegalStateException, which surfaces as a 500.
    if (!element.isJsonObject()) {
      throw new InvalidJsonException();
    }

    if (this.fromJsonHelper.parameterExists("clientId", element)) {
      final List<ApiParameterError> errors = new ArrayList<>();
      final DataValidatorBuilder baseDataValidator =
          new DataValidatorBuilder(errors).resource("loan.simulation");
      baseDataValidator
          .reset()
          .parameter("clientId")
          .failWithCode(
              "not.allowed.for.public.simulation",
              "clientId is not allowed in public loan simulation requests");
      throw new PlatformApiDataValidationException(errors);
    }
  }
}
