/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionRequest;
import org.apache.fineract.selfservice.account.service.SelfServiceSinpeEnrollmentWritePlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfSinpeEnrollmentApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
  @Mock private SelfServiceSinpeEnrollmentWritePlatformService writePlatformService;

  @InjectMocks private SelfSinpeEnrollmentApiResource resource;

  @BeforeEach
  void setUp() {
    when(context.authenticatedSelfServiceUser()).thenReturn(mock(AppSelfServiceUser.class));
    when(toApiJsonSerializer.serialize(any(CommandProcessingResult.class))).thenReturn("{}");
  }

  // --- body that isn't a JSON object ---

  @Test
  void requestEnrollment_blankBody_isRejected() {
    assertThrows(InvalidJsonException.class, () -> resource.requestEnrollment("  "));
    verifyNoInteractions(writePlatformService);
  }

  @Test
  void requestEnrollment_nullBody_isRejected() {
    assertThrows(InvalidJsonException.class, () -> resource.requestEnrollment(null));
    verifyNoInteractions(writePlatformService);
  }

  @Test
  void requestEnrollment_nonObjectBody_isRejected() {
    assertThrows(InvalidJsonException.class, () -> resource.requestEnrollment("[]"));
    assertThrows(InvalidJsonException.class, () -> resource.requestEnrollment("\"88887777\""));
    verifyNoInteractions(writePlatformService);
  }

  // --- missing, null or blank fields ---

  @Test
  void requestEnrollment_missingMobileNumber_isRejected() {
    assertFieldErrors(() -> resource.requestEnrollment("{}"), "mobileNumber");
  }

  @Test
  void requestEnrollment_nullMobileNumber_isRejected() {
    assertFieldErrors(
        () -> resource.requestEnrollment("{\"mobileNumber\":null}"), "mobileNumber");
  }

  @Test
  void requestEnrollment_objectMobileNumber_isRejected() {
    assertFieldErrors(
        () -> resource.requestEnrollment("{\"mobileNumber\":{\"n\":\"1\"}}"), "mobileNumber");
  }

  @Test
  void confirmEnrollment_missingBothFields_reportsBoth() {
    assertFieldErrors(() -> resource.confirmEnrollment("{}"), "mobileNumber", "otp");
  }

  @Test
  void confirmEnrollment_blankOtp_isRejected() {
    assertFieldErrors(
        () -> resource.confirmEnrollment("{\"mobileNumber\":\"88887777\",\"otp\":\" \"}"), "otp");
  }

  @Test
  void createSubscription_missingOtp_isRejected() {
    assertFieldErrors(() -> resource.createSubscription("{\"alias\":\"me\"}"), "otp");
  }

  @Test
  void editSubscription_nullOtp_isRejected() {
    assertFieldErrors(() -> resource.editSubscription("{\"otp\":null}"), "otp");
  }

  @Test
  void requestDeleteSubscription_missingMobileNumber_isRejected() {
    assertFieldErrors(() -> resource.requestDeleteSubscription("{}"), "mobileNumber");
  }

  // --- valid requests still go through ---

  @Test
  void requestEnrollment_validBody_passesMobileNumber() {
    resource.requestEnrollment("{\"mobileNumber\":\"88887777\"}");
    verify(writePlatformService).requestEnrollment("88887777");
  }

  @Test
  void requestEnrollment_numericMobileNumber_isStillAccepted() {
    resource.requestEnrollment("{\"mobileNumber\":88887777}");
    verify(writePlatformService).requestEnrollment("88887777");
  }

  @Test
  void confirmEnrollment_validBody_passesBothFields() {
    resource.confirmEnrollment("{\"mobileNumber\":\"88887777\",\"otp\":\"123456\"}");
    verify(writePlatformService).confirmEnrollment("88887777", "123456");
  }

  @Test
  void createSubscription_validBody_passesOtp() {
    resource.createSubscription("{\"otp\":\"123456\"}");
    verify(writePlatformService)
        .createSubscription(any(SinpeSubscriptionRequest.class), eq("123456"));
  }

  @Test
  void editSubscription_validBody_passesOtp() {
    resource.editSubscription("{\"otp\":\"123456\"}");
    verify(writePlatformService)
        .editSubscription(any(SinpeSubscriptionEditRequest.class), eq("123456"));
  }

  @Test
  void requestDeleteSubscription_validBody_passesMobileNumber() {
    resource.requestDeleteSubscription("{\"mobileNumber\":\"88887777\"}");
    verify(writePlatformService).requestDeleteSubscription("88887777");
  }

  private void assertFieldErrors(Runnable call, String... expectedFields) {
    PlatformApiDataValidationException ex =
        assertThrows(PlatformApiDataValidationException.class, call::run);
    List<String> fields = ex.getErrors().stream().map(ApiParameterError::getParameterName).toList();
    assertEquals(List.of(expectedFields), fields);
    verifyNoInteractions(writePlatformService);
  }
}
