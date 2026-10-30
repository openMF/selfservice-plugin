/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * MX-516: an external beneficiary is only listed with account type 3 or 4, and a same-bank one points
 * to a loan (1) or savings (2) account, so other combinations must be rejected instead of being saved
 * as beneficiaries the user can never see.
 */
class SelfBeneficiariesTPTDataValidatorTest {

  private final SelfBeneficiariesTPTDataValidator validator =
      new SelfBeneficiariesTPTDataValidator(new FromJsonHelper());

  private static String request(final String paymentType, final int accountType) {
    return "{\"locale\":\"en\",\"name\":\"Ana\",\"paymentType\":\""
        + paymentType
        + "\",\"currency\":\"CRC\",\"accountType\":"
        + accountType
        + ",\"accountNumber\":\"000000012\",\"officeName\":\"Head Office\"}";
  }

  @ParameterizedTest
  @CsvSource({
    "SAME_BANK, 1",
    "SAME_BANK, 2",
    "SINPE, 3",
    "SINPE, 4",
    "SINPE_MOVIL, 3",
    "SINPE_MOVIL, 4",
    "PIN, 3",
    "PIN, 4"
  })
  void accountTypesThatMatchThePaymentTypeAreAccepted(
      final String paymentType, final int accountType) {
    assertDoesNotThrow(() -> validator.validateForCreate(request(paymentType, accountType)));
  }

  @ParameterizedTest
  @CsvSource({
    "SAME_BANK, 3",
    "SAME_BANK, 4",
    "SINPE, 1",
    "SINPE, 2",
    "SINPE_MOVIL, 1",
    "SINPE_MOVIL, 2",
    "PIN, 1",
    "PIN, 2"
  })
  void accountTypesThatDoNotMatchThePaymentTypeAreRejected(
      final String paymentType, final int accountType) {
    PlatformApiDataValidationException error =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(request(paymentType, accountType)));
    assertEquals("accountType", error.getErrors().get(0).getParameterName());
  }
}
