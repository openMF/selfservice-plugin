/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelfLoansDataValidatorTest {

  private SelfLoansDataValidator validator;

  @BeforeEach
  void setUp() {
    FromJsonHelper fromJsonHelper = new FromJsonHelper();
    validator = new SelfLoansDataValidator(fromJsonHelper);
  }

  // --- validateLoanApplication ---

  @Test
  void validateLoanApplication_shouldThrowOnBlankJson() {
    assertThrows(InvalidJsonException.class, () -> validator.validateLoanApplication(""));
    assertThrows(InvalidJsonException.class, () -> validator.validateLoanApplication("   "));
  }

  @Test
  void validateLoanApplication_shouldThrowOnMissingClientId() {
    String json = "{\"loanType\": \"individual\"}";
    assertThrows(
        PlatformApiDataValidationException.class, () -> validator.validateLoanApplication(json));
  }

  @Test
  void validateLoanApplication_shouldThrowOnMissingLoanType() {
    String json = "{\"clientId\": \"1\"}";
    assertThrows(
        PlatformApiDataValidationException.class, () -> validator.validateLoanApplication(json));
  }

  @Test
  void validateLoanApplication_shouldThrowOnInvalidLoanType() {
    String json = "{\"loanType\": \"group\", \"clientId\": \"1\"}";
    assertThrows(
        PlatformApiDataValidationException.class, () -> validator.validateLoanApplication(json));
  }

  @Test
  void validateLoanApplication_shouldReturnClientIdOnValidInput() {
    String json = "{\"loanType\": \"individual\", \"clientId\": \"42\"}";
    HashMap<String, Object> result = validator.validateLoanApplication(json);
    assertEquals(42L, result.get("clientId"));
  }

  // --- validateModifyLoanApplication ---

  @Test
  void validateModifyLoanApplication_shouldReturnEmptyMapWhenNoParams() {
    String json = "{}";
    HashMap<String, Object> result = validator.validateModifyLoanApplication(json);
    assertFalse(result.containsKey("clientId"));
  }

  @Test
  void validateModifyLoanApplication_shouldReturnClientIdWhenPresent() {
    String json = "{\"clientId\": \"99\"}";
    HashMap<String, Object> result = validator.validateModifyLoanApplication(json);
    assertEquals(99L, result.get("clientId"));
  }

  @Test
  void validateModifyLoanApplication_shouldThrowOnInvalidLoanType() {
    String json = "{\"loanType\": \"group\"}";
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateModifyLoanApplication(json));
  }

  @Test
  void validateModifyLoanApplication_shouldAcceptIndividualLoanType() {
    String json = "{\"loanType\": \"individual\", \"clientId\": \"5\"}";
    HashMap<String, Object> result = validator.validateModifyLoanApplication(json);
    assertEquals(5L, result.get("clientId"));
  }

  // --- MX-530: a blank or non-object body is a 400, not a 500 ---

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "[]", "[{\"clientId\": 1}]", "\"x\"", "123", "null", "true"})
  void validateModifyLoanApplication_shouldThrowOnBlankOrNonObjectJson(String json) {
    assertThrows(
        InvalidJsonException.class, () -> validator.validateModifyLoanApplication(json));
  }

  // --- MX-525: a loan cannot be attached to a group ---

  @Test
  void validateModifyLoanApplication_shouldThrowOnGroupId() {
    String json = "{\"loanType\": \"individual\", \"groupId\": 3}";
    PlatformApiDataValidationException error =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateModifyLoanApplication(json));
    assertEquals("groupId", error.getErrors().get(0).getParameterName());
  }
}
