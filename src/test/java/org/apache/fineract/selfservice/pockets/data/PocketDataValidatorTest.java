/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.pockets.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PocketDataValidatorTest {

  private PocketDataValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PocketDataValidator(new FromJsonHelper());
  }

  // --- validateForLinkingAccounts ---

  @Test
  void validateForLinkingAccounts_shouldThrowOnBlankJson() {
    assertThrows(InvalidJsonException.class, () -> validator.validateForLinkingAccounts(""));
    assertThrows(InvalidJsonException.class, () -> validator.validateForLinkingAccounts("   "));
  }

  @Test
  void validateForLinkingAccounts_shouldAcceptUpperCaseAccountType() {
    String json = "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"LOAN\"}]}";
    assertDoesNotThrow(() -> validator.validateForLinkingAccounts(json));
  }

  @Test
  void validateForLinkingAccounts_shouldAcceptLowerCaseAccountType() {
    String json = "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"loan\"}]}";
    assertDoesNotThrow(() -> validator.validateForLinkingAccounts(json));
  }

  @Test
  void validateForLinkingAccounts_shouldAcceptSavingsAndShares() {
    String json =
        "{\"accountsDetail\":[{\"accountId\":1,\"accountType\":\"SAVINGS\"},"
            + "{\"accountId\":2,\"accountType\":\"SHARES\"}]}";
    assertDoesNotThrow(() -> validator.validateForLinkingAccounts(json));
  }

  @Test
  void validateForLinkingAccounts_shouldThrowOnMissingAccountsDetail() {
    assertThrows(
        PlatformApiDataValidationException.class, () -> validator.validateForLinkingAccounts("{}"));
  }

  @Test
  void validateForLinkingAccounts_shouldThrowOnEmptyAccountsDetail() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForLinkingAccounts("{\"accountsDetail\":[]}"));
  }

  @Test
  void validateForLinkingAccounts_shouldThrowOnInvalidAccountType() {
    String json = "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"CHECKING\"}]}";
    assertThrows(
        PlatformApiDataValidationException.class, () -> validator.validateForLinkingAccounts(json));
  }

  @Test
  void validateForLinkingAccounts_shouldThrowOnUnsupportedParameter() {
    String json =
        "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"LOAN\"}],\"foo\":\"bar\"}";
    assertThrows(
        UnsupportedParameterException.class, () -> validator.validateForLinkingAccounts(json));
  }

  @Test
  void validateForLinkingAccounts_shouldThrowOnRootLevelAccountId() {
    // accountId/accountType are only valid nested inside accountsDetail, not at the root.
    String json =
        "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"LOAN\"}],\"accountId\":5}";
    assertThrows(
        UnsupportedParameterException.class, () -> validator.validateForLinkingAccounts(json));
  }

  @Test
  void validateForLinkingAccounts_shouldThrowOnUnknownFieldInAccountsDetail() {
    String json = "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"LOAN\",\"foo\":1}]}";
    assertThrows(
        UnsupportedParameterException.class, () -> validator.validateForLinkingAccounts(json));
  }

  // --- validateForDeLinkingAccounts ---

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnBlankJson() {
    assertThrows(InvalidJsonException.class, () -> validator.validateForDeLinkingAccounts(""));
    assertThrows(InvalidJsonException.class, () -> validator.validateForDeLinkingAccounts("  "));
  }

  @Test
  void validateForDeLinkingAccounts_shouldAcceptValidPayload() {
    String json = "{\"pocketAccountMappingIds\":[10,11]}";
    assertDoesNotThrow(() -> validator.validateForDeLinkingAccounts(json));
  }

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnMissingList() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForDeLinkingAccounts("{}"));
  }

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnEmptyList() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForDeLinkingAccounts("{\"pocketAccountMappingIds\":[]}"));
  }

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnUnsupportedParameter() {
    assertThrows(
        UnsupportedParameterException.class,
        () -> validator.validateForDeLinkingAccounts("{\"foo\":[1]}"));
  }

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnNonNumericObjectElement() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForDeLinkingAccounts("{\"pocketAccountMappingIds\":[{}]}"));
  }

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnNullElement() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForDeLinkingAccounts("{\"pocketAccountMappingIds\":[null]}"));
  }

  @Test
  void validateForDeLinkingAccounts_shouldThrowOnNonNumericStringElement() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateForDeLinkingAccounts("{\"pocketAccountMappingIds\":[\"abc\"]}"));
  }
}
