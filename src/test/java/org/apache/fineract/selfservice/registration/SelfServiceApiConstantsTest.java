/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SelfServiceApiConstantsTest {

  @Test
  void registrationRequestDataParameters_shouldContainAllRequiredFields() {
    assertTrue(SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("username"));
    assertTrue(
        SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("accountNumber"));
    assertTrue(SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("password"));
    assertTrue(SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("firstName"));
    assertTrue(SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("lastName"));
    assertTrue(SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("email"));
    assertTrue(
        SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains(
            "authenticationMode"));
    assertTrue(SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.contains("middleName"));
  }

  @Test
  void registrationRequestDataParameters_shouldBeImmutable() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> SelfServiceApiConstants.REGISTRATION_REQUEST_DATA_PARAMETERS.add("illegal"));
  }

  @Test
  void createUserRequestDataParameters_shouldContainRequiredFields() {
    assertTrue(SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS.contains("requestId"));
    assertTrue(
        SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS.contains(
            "authenticationToken"));
    assertTrue(
        SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS.contains(
            "externalAuthenticationToken"));
    assertEquals(3, SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS.size());
  }

  @Test
  void createUserRequestDataParameters_shouldBeImmutable() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> SelfServiceApiConstants.CREATE_USER_REQUEST_DATA_PARAMETERS.add("illegal"));
  }

  @Test
  void selfServiceUserRole_shouldBeCorrect() {
    assertEquals("Self Service User", SelfServiceApiConstants.SELF_SERVICE_USER_ROLE);
  }

  @Test
  void forgotPasswordRenewDataParameters_shouldContainRequiredFields() {
    assertTrue(SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.contains("requestId"));
    assertTrue(
        SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.contains(
            "authenticationToken"));
    assertTrue(
        SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.contains(
            "externalAuthenticationToken"));
    assertTrue(SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.contains("password"));
    assertTrue(
        SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.contains("repeatPassword"));
    assertEquals(5, SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.size());
  }

  @Test
  void forgotPasswordRenewDataParameters_shouldBeImmutable() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS.add("illegal"));
  }

  @Test
  void createRequestSuccessMessage_shouldBeCorrect() {
    assertEquals(
        "Self service request created.", SelfServiceApiConstants.createRequestSuccessMessage);
  }
}
