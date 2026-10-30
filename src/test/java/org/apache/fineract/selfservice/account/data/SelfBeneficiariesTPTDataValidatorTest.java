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
 *
 * <p>MX-519: the destination of an external beneficiary must be a Costa Rican IBAN (SINPE, PIN) or an
 * 8-digit phone number (SINPE_MOVIL).
 */
class SelfBeneficiariesTPTDataValidatorTest {

  private static final String IBAN = "CR21015201001026284066";
  private static final String PHONE = "88887777";

  private final SelfBeneficiariesTPTDataValidator validator =
      new SelfBeneficiariesTPTDataValidator(new FromJsonHelper());

  private static String request(final String paymentType, final int accountType) {
    final String destination =
        switch (paymentType) {
          case "SINPE", "PIN" -> "\"destinationIban\":\"" + IBAN + "\"";
          case "SINPE_MOVIL" -> "\"phoneNumber\":\"" + PHONE + "\"";
          default -> "\"accountNumber\":\"000000012\",\"officeName\":\"Head Office\"";
        };
    return "{\"locale\":\"en\",\"name\":\"Ana\",\"paymentType\":\""
        + paymentType
        + "\",\"currency\":\"CRC\",\"accountType\":"
        + accountType
        + ","
        + destination
        + "}";
  }

  private static String external(
      final String paymentType, final String destinationParam, final String destination) {
    return "{\"locale\":\"en\",\"name\":\"Ana\",\"paymentType\":\""
        + paymentType
        + "\",\"currency\":\"CRC\",\"accountType\":3,\""
        + destinationParam
        + "\":\""
        + destination
        + "\"}";
  }

  @ParameterizedTest
  @CsvSource({
    "SINPE, destinationIban, CR21015201001026284066, CR21015201001026284066",
    "SINPE, destinationIban, cr21 0152 0100 1026 2840 66, CR21015201001026284066",
    "PIN, destinationIban, CR21015201001026284066, CR21015201001026284066",
    "PIN, accountNumber, CR21015201001026284066, CR21015201001026284066",
    "SINPE_MOVIL, phoneNumber, 88887777, 88887777",
    "SINPE_MOVIL, phoneNumber, 8888 7777, 88887777"
  })
  void wellFormedExternalDestinationsAreAcceptedWithoutSpaces(
      final String paymentType,
      final String destinationParam,
      final String destination,
      final String stored) {
    assertEquals(
        stored,
        validator
            .validateForCreate(external(paymentType, destinationParam, destination))
            .get("customAccountNumber"));
  }

  @ParameterizedTest
  @CsvSource({
    "SINPE, destinationIban, 123",
    "SINPE, destinationIban, CR2101520100102628406",
    "SINPE, destinationIban, CR210152010010262840661",
    "SINPE, destinationIban, ES9121000418450200051332",
    "PIN, destinationIban, CR21O15201001026284066",
    "PIN, accountNumber, 000000012",
    "SINPE_MOVIL, phoneNumber, abc",
    "SINPE_MOVIL, phoneNumber, 8888777",
    "SINPE_MOVIL, phoneNumber, 888877776",
    "SINPE_MOVIL, phoneNumber, +50688887777"
  })
  void malformedExternalDestinationsAreRejected(
      final String paymentType, final String destinationParam, final String destination) {
    PlatformApiDataValidationException error =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateForCreate(external(paymentType, destinationParam, destination)));
    assertEquals(destinationParam, error.getErrors().get(0).getParameterName());
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
