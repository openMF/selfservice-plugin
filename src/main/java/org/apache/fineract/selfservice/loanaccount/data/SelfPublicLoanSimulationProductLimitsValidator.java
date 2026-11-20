/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.ResourceNotFoundException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.loanproduct.serialization.LoanProductDataValidator;
import org.apache.fineract.selfservice.loanaccount.service.SelfPublicLoanProductReadService;
import org.springframework.stereotype.Component;

/**
 * Applies a loan product's limits to a public loan simulation, as a loan application does.
 *
 * <p>The public simulation calls {@code LoanScheduleAssembler} directly, which skips {@code
 * LoanApplicationValidator} and with it the product's limits, so a simulation could show a schedule
 * the bank would never grant. This uses Fineract's own {@link
 * LoanProductDataValidator#validateMinMaxConstraints} for the principal, the number of repayments
 * and the interest rate per period.
 */
@Component
@RequiredArgsConstructor
public class SelfPublicLoanSimulationProductLimitsValidator {

  /**
   * The only request fields the limits are checked against. Fineract's validator also reads
   * minPrincipal, maxPrincipal, the repayment and rate bounds and isLinkedToFloatingInterestRates
   * from the request (it is shared with the loan product form), so a caller could send wider limits.
   */
  private static final List<String> SIMULATION_INPUTS =
      List.of("principal", "numberOfRepayments", "interestRatePerPeriod", "locale");

  private static final String INTEREST_RATE_FREQUENCY_TYPE = "interestRateFrequencyType";

  private final FromJsonHelper fromJsonHelper;
  private final SelfPublicLoanProductReadService loanProductReadService;
  private final LoanProductRepository loanProductRepository;
  private final LoanProductDataValidator loanProductDataValidator;

  /**
   * @throws PlatformApiDataValidationException if {@code productId} is missing or a value is outside
   *     the product's limits
   * @throws ResourceNotFoundException if the product does not exist or is not active (only active
   *     products can be simulated, as on the products and template endpoints)
   */
  public void validateWithinProductLimits(final JsonElement element) {
    final Long productId = this.fromJsonHelper.extractLongNamed("productId", element);
    if (productId == null) {
      final List<ApiParameterError> errors = new ArrayList<>();
      new DataValidatorBuilder(errors)
          .resource("loan.simulation")
          .parameter("productId")
          .value(null)
          .notNull();
      throw new PlatformApiDataValidationException(errors);
    }
    final boolean active =
        this.loanProductReadService.retrieveAllActiveLoanProducts().stream()
            .anyMatch(product -> productId.equals(product.getId()));
    final LoanProduct loanProduct =
        active ? this.loanProductRepository.findById(productId).orElse(null) : null;
    if (loanProduct == null) {
      throw new ResourceNotFoundException("loanProduct", "id", new Object[] {productId});
    }

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource("loan");
    validateInterestRateFrequency(element, loanProduct, validator);
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
    // A visitor has no loans of the product, and Fineract gives such a client the product's own
    // limits, also for a product with borrower cycles (their variations apply from cycle 1). The
    // borrower-cycle overload is not used for cycle 0: when the product has no min/max limits, it
    // takes the default number of repayments as the maximum interest rate.
    this.loanProductDataValidator.validateMinMaxConstraints(
        simulationInputs(element), validator, loanProduct);
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }

  /**
   * The product's interest rate limits are per its own interest period, and the schedule uses the
   * period sent in the request, so a rate sent per another period would not be limited (2 per month
   * on a product limited to 2 per year).
   */
  private void validateInterestRateFrequency(
      final JsonElement element,
      final LoanProduct loanProduct,
      final DataValidatorBuilder validator) {
    if (!this.fromJsonHelper.parameterExists(INTEREST_RATE_FREQUENCY_TYPE, element)) {
      return;
    }
    final Integer frequencyType =
        this.fromJsonHelper.extractIntegerWithLocaleNamed(INTEREST_RATE_FREQUENCY_TYPE, element);
    validator
        .reset()
        .parameter(INTEREST_RATE_FREQUENCY_TYPE)
        .value(frequencyType)
        // Sent as null, Fineract's schedule takes it as an invalid period and charges no interest
        .notNull()
        .isOneOfTheseValues(loanProduct.getInterestPeriodFrequencyType().getValue());
  }

  /** A copy of the request with only the {@link #SIMULATION_INPUTS} that were sent. */
  static JsonObject simulationInputs(final JsonElement element) {
    final JsonObject inputs = new JsonObject();
    if (element != null && element.isJsonObject()) {
      for (final String name : SIMULATION_INPUTS) {
        if (element.getAsJsonObject().has(name)) {
          inputs.add(name, element.getAsJsonObject().get(name));
        }
      }
    }
    return inputs;
  }
}
