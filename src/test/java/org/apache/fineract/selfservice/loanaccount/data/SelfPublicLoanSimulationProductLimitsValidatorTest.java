/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.ResourceNotFoundException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.selfservice.loanaccount.service.SelfPublicLoanProductReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The limits themselves are applied by Fineract's LoanProductDataValidator (a final class), so they
 * are covered by SelfPublicLoanSimulationIntegrationTest; these tests cover the product lookup.
 */
@ExtendWith(MockitoExtension.class)
class SelfPublicLoanSimulationProductLimitsValidatorTest {

  @Mock private SelfPublicLoanProductReadService loanProductReadService;
  @Mock private LoanProductRepository loanProductRepository;

  private SelfPublicLoanSimulationProductLimitsValidator validator;

  @BeforeEach
  void setUp() {
    validator =
        new SelfPublicLoanSimulationProductLimitsValidator(
            new FromJsonHelper(), loanProductReadService, loanProductRepository, null);
  }

  private static JsonElement json(String body) {
    return JsonParser.parseString(body);
  }

  private static SelfPublicLoanProductData product(Long id) {
    return SelfPublicLoanProductData.builder().id(id).name("Loan " + id).build();
  }

  @Test
  void withoutProductId_isRejected() {
    PlatformApiDataValidationException exception =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateWithinProductLimits(json("{\"principal\": 10000}")));

    assertEquals("productId", exception.getErrors().get(0).getParameterName());
    verifyNoInteractions(loanProductReadService, loanProductRepository);
  }

  @Test
  void productThatIsNotActive_isNotFound() {
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(List.of(product(1L)));

    assertThrows(
        ResourceNotFoundException.class,
        () -> validator.validateWithinProductLimits(json("{\"productId\": 7}")));
    verifyNoInteractions(loanProductRepository);
  }

  @Test
  void activeProductMissingFromTheRepository_isNotFound() {
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(List.of(product(1L)));
    when(loanProductRepository.findById(1L)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> validator.validateWithinProductLimits(json("{\"productId\": 1}")));
    verify(loanProductRepository).findById(1L);
  }

  @Test
  void interestRateFrequencyOtherThanTheProducts_isRejected() {
    LoanProduct loanProduct = mock(LoanProduct.class);
    when(loanProduct.getInterestPeriodFrequencyType()).thenReturn(PeriodFrequencyType.MONTHS);
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(List.of(product(1L)));
    when(loanProductRepository.findById(1L)).thenReturn(Optional.of(loanProduct));

    PlatformApiDataValidationException exception =
        assertThrows(
            PlatformApiDataValidationException.class,
            () ->
                validator.validateWithinProductLimits(
                    json(
                        """
                        {"productId": 1, "interestRatePerPeriod": 2,
                         "interestRateFrequencyType": 3, "locale": "en"}
                        """)));

    assertEquals("interestRateFrequencyType", exception.getErrors().get(0).getParameterName());
  }

  @Test
  void interestRateFrequencySentAsNull_isRejected() {
    LoanProduct loanProduct = mock(LoanProduct.class);
    when(loanProduct.getInterestPeriodFrequencyType()).thenReturn(PeriodFrequencyType.MONTHS);
    when(loanProductReadService.retrieveAllActiveLoanProducts()).thenReturn(List.of(product(1L)));
    when(loanProductRepository.findById(1L)).thenReturn(Optional.of(loanProduct));

    PlatformApiDataValidationException exception =
        assertThrows(
            PlatformApiDataValidationException.class,
            () ->
                validator.validateWithinProductLimits(
                    json(
                        """
                        {"productId": 1, "interestRatePerPeriod": 2,
                         "interestRateFrequencyType": null, "locale": "en"}
                        """)));

    assertEquals("interestRateFrequencyType", exception.getErrors().get(0).getParameterName());
  }

  @Test
  void simulationInputs_dropsBoundsSentInTheRequest() {
    JsonObject inputs =
        SelfPublicLoanSimulationProductLimitsValidator.simulationInputs(
            json(
                """
                {"productId": 1, "principal": 5000000, "maxPrincipal": 5000000,
                 "numberOfRepayments": 60, "maxNumberOfRepayments": 60,
                 "interestRatePerPeriod": 5, "maxInterestRatePerPeriod": 5,
                 "isLinkedToFloatingInterestRates": true, "locale": "en"}
                """));

    assertEquals(
        json(
            """
            {"principal": 5000000, "numberOfRepayments": 60, "interestRatePerPeriod": 5,
             "locale": "en"}
            """),
        inputs);
  }

  @Test
  void simulationInputs_keepsOnlyWhatWasSent() {
    assertEquals(
        json("{\"principal\": 10000}"),
        SelfPublicLoanSimulationProductLimitsValidator.simulationInputs(
            json("{\"productId\": 1, \"principal\": 10000}")));
  }
}
