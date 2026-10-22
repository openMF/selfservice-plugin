/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.junit.jupiter.api.Test;

class StepUpDataValidatorTest {

  private final StepUpDataValidator validator = new StepUpDataValidator(new FromJsonHelper());

  private static final String TPT_PAYLOAD =
      "{\"fromAccountId\":1,\"fromAccountType\":2,\"toAccountId\":2,\"toAccountType\":2,"
          + "\"transferAmount\":100.50}";

  private static List<String> errorParameters(final PlatformApiDataValidationException ex) {
    return ex.getErrors().stream().map(ApiParameterError::getParameterName).toList();
  }

  @Test
  void create_acceptsAValidTransferRequest() {
    final StepUpDataValidator.CreateChallengeRequest request =
        validator.validateCreateChallenge(
            "{\"action\":\"TPT_TRANSFER\",\"channel\":\"SMS\",\"payload\":" + TPT_PAYLOAD + "}");

    assertEquals(StepUpAction.TPT_TRANSFER, request.action());
    assertEquals(StepUpChannel.SMS, request.channel());
    assertEquals("1", request.payloadValues().get("fromAccountId"));
    assertEquals("100.50", request.payloadValues().get("transferAmount"));
  }

  @Test
  void create_isCaseInsensitiveForActionAndChannel() {
    final StepUpDataValidator.CreateChallengeRequest request =
        validator.validateCreateChallenge(
            "{\"action\":\" tpt_transfer \",\"channel\":\"email\",\"payload\":"
                + TPT_PAYLOAD
                + "}");
    assertEquals(StepUpAction.TPT_TRANSFER, request.action());
    assertEquals(StepUpChannel.EMAIL, request.channel());
  }

  @Test
  void create_changePasswordNeedsNoPayload() {
    final StepUpDataValidator.CreateChallengeRequest request =
        validator.validateCreateChallenge("{\"action\":\"CHANGE_PASSWORD\",\"channel\":\"EMAIL\"}");
    assertEquals(StepUpAction.CHANGE_PASSWORD, request.action());
    assertTrue(request.payloadValues().isEmpty());
  }

  @Test
  void create_reportsEveryMissingPayloadField() {
    final PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () ->
                validator.validateCreateChallenge(
                    "{\"action\":\"TPT_TRANSFER\",\"channel\":\"SMS\",\"payload\":"
                        + "{\"fromAccountId\":1,\"transferAmount\":5}}"));

    final List<String> parameters = errorParameters(ex);
    assertTrue(parameters.contains("payload.fromAccountType"), parameters.toString());
    assertTrue(parameters.contains("payload.toAccountId"));
    assertTrue(parameters.contains("payload.toAccountType"));
    assertFalse(parameters.contains("payload.fromAccountId"));
    assertFalse(parameters.contains("payload.transferAmount"));
  }

  @Test
  void create_aTransferWithoutAnyPayload_isRejected() {
    final PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () ->
                validator.validateCreateChallenge(
                    "{\"action\":\"TPT_TRANSFER\",\"channel\":\"SMS\"}"));
    assertTrue(errorParameters(ex).contains("payload.transferAmount"));
  }

  @Test
  void create_rejectsAnUnknownActionOrChannel() {
    assertTrue(
        errorParameters(
                assertThrows(
                    PlatformApiDataValidationException.class,
                    () ->
                        validator.validateCreateChallenge(
                            "{\"action\":\"DELETE_EVERYTHING\",\"channel\":\"SMS\"}")))
            .contains("action"));
    assertTrue(
        errorParameters(
                assertThrows(
                    PlatformApiDataValidationException.class,
                    () ->
                        validator.validateCreateChallenge(
                            "{\"action\":\"CHANGE_PASSWORD\",\"channel\":\"CARRIER_PIGEON\"}")))
            .contains("channel"));
  }

  @Test
  void create_rejectsMissingActionAndChannel() {
    final PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> validator.validateCreateChallenge("{}"));
    assertTrue(errorParameters(ex).containsAll(List.of("action", "channel")));
  }

  @Test
  void create_rejectsAPayloadThatIsNotAnObject() {
    final PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () ->
                validator.validateCreateChallenge(
                    "{\"action\":\"CHANGE_PASSWORD\",\"channel\":\"SMS\",\"payload\":\"x\"}"));
    assertTrue(errorParameters(ex).contains("payload"));
  }

  @Test
  void create_rejectsUnsupportedParameters() {
    assertThrows(
        UnsupportedParameterException.class,
        () ->
            validator.validateCreateChallenge(
                "{\"action\":\"CHANGE_PASSWORD\",\"channel\":\"SMS\",\"otp\":\"123456\"}"));
  }

  @Test
  void create_rejectsABlankBody() {
    assertThrows(InvalidJsonException.class, () -> validator.validateCreateChallenge(" "));
    assertThrows(InvalidJsonException.class, () -> validator.validateCreateChallenge(null));
  }

  @Test
  void verify_returnsTheTrimmedOtp() {
    assertEquals("123456", validator.validateVerify("{\"otp\":\" 123456 \"}"));
    assertEquals("042917", validator.validateVerify("{\"otp\":\"042917\"}"));
  }

  @Test
  void verify_rejectsBlankMissingOrTooLongOtp() {
    assertTrue(
        errorParameters(
                assertThrows(
                    PlatformApiDataValidationException.class,
                    () -> validator.validateVerify("{\"otp\":\"  \"}")))
            .contains("otp"));
    assertThrows(
        PlatformApiDataValidationException.class, () -> validator.validateVerify("{\"otp\":null}"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> validator.validateVerify("{\"otp\":\"12345678901234567\"}"));
  }

  @Test
  void verify_rejectsUnsupportedParametersAndBlankBody() {
    assertThrows(
        UnsupportedParameterException.class,
        () -> validator.validateVerify("{\"otp\":\"123456\",\"extra\":1}"));
    assertThrows(InvalidJsonException.class, () -> validator.validateVerify(""));
  }
}
