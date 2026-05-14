/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfPublicLoanSimulationDataValidatorTest {

  private SelfPublicLoanSimulationDataValidator validator;

  @BeforeEach
  void setUp() {
    validator = new SelfPublicLoanSimulationDataValidator(new FromJsonHelper());
  }

  @Test
  void validateCommand_calculateLoanSchedule_passes() {
    String validJson = "{\"productId\": 1, \"principal\": 10000}";
    assertDoesNotThrow(
        () -> validator.validatePublicSimulationRequest("calculateLoanSchedule", validJson));
  }

  @Test
  void validateCommand_null_throws() {
    String validJson = "{\"productId\": 1}";
    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> validator.validatePublicSimulationRequest(null, validJson));
  }

  @Test
  void validateCommand_empty_throws() {
    String validJson = "{\"productId\": 1}";
    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> validator.validatePublicSimulationRequest("", validJson));
  }

  @Test
  void validateCommand_submit_throws() {
    String validJson = "{\"productId\": 1}";
    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> validator.validatePublicSimulationRequest("submit", validJson));
  }

  @Test
  void validateCommand_randomString_throws() {
    String validJson = "{\"productId\": 1}";
    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> validator.validatePublicSimulationRequest("createLoanApplication", validJson));
  }

  @Test
  void validateBody_withClientId_throws() {
    String jsonWithClientId = "{\"productId\": 1, \"principal\": 10000, \"clientId\": 42}";
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validatePublicSimulationRequest("calculateLoanSchedule", jsonWithClientId));
  }

  @Test
  void validateBody_withoutClientId_passes() {
    String cleanJson = "{\"productId\": 1, \"principal\": 10000}";
    assertDoesNotThrow(
        () -> validator.validatePublicSimulationRequest("calculateLoanSchedule", cleanJson));
  }

  @Test
  void validateBody_blankJson_throws() {
    assertThrows(
        InvalidJsonException.class,
        () -> validator.validatePublicSimulationRequest("calculateLoanSchedule", ""));
  }

  @Test
  void validateBody_nullJson_throws() {
    assertThrows(
        InvalidJsonException.class,
        () -> validator.validatePublicSimulationRequest("calculateLoanSchedule", null));
  }

  @Test
  void validateBody_malformedJson_throwsInvalidJsonException() {
    assertThrows(
        InvalidJsonException.class,
        () -> validator.validatePublicSimulationRequest("calculateLoanSchedule", "{bad json}"));
  }
}
