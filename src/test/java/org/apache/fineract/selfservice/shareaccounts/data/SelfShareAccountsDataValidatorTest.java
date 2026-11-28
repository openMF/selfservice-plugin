/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.shareaccounts.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfShareAccountsDataValidatorTest {

  private SelfShareAccountsDataValidator validator;

  @BeforeEach
  void setUp() {
    FromJsonHelper fromJsonHelper = new FromJsonHelper();
    validator = new SelfShareAccountsDataValidator(fromJsonHelper);
  }

  @Test
  void validateShareAccountApplication_shouldThrowOnBlankJson() {
    assertThrows(InvalidJsonException.class, () -> validator.validateShareAccountApplication(""));
    assertThrows(
        InvalidJsonException.class, () -> validator.validateShareAccountApplication("   "));
  }

  @Test
  void validateShareAccountApplication_shouldThrowOnMissingClientId() {
    String json = "{\"productId\": 1}";
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateShareAccountApplication(json));
  }

  @Test
  void validateShareAccountApplication_shouldReturnClientIdOnValidInput() {
    String json = "{\"clientId\": \"15\"}";
    HashMap<String, Object> result = validator.validateShareAccountApplication(json);
    assertEquals(15L, result.get("clientId"));
    assertNull(result.get("savingsAccountId"));
  }

  @Test
  void validateShareAccountApplication_shouldExtractSavingsAccountIdWhenPresent() {
    String json = "{\"clientId\": \"15\", \"savingsAccountId\": 25}";
    HashMap<String, Object> result = validator.validateShareAccountApplication(json);
    assertEquals(15L, result.get("clientId"));
    assertEquals(25L, result.get("savingsAccountId"));
  }
}
