/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.data;

import com.google.gson.JsonElement;
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
    if (loanProduct.isUseBorrowerCycle()) {
      // A public simulation has no client, so the first cycle's limits apply
      this.loanProductDataValidator.validateMinMaxConstraints(element, validator, loanProduct, 0);
    } else {
      this.loanProductDataValidator.validateMinMaxConstraints(element, validator, loanProduct);
    }
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }
}
