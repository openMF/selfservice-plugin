/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import org.apache.fineract.portfolio.client.domain.Client;
import org.junit.jupiter.api.Test;

class SelfServiceRegistrationTest {

  @Test
  void instance_shouldCreateRegistrationWithAllFields() {
    Client client = mock(Client.class);
    SelfServiceRegistration reg =
        SelfServiceRegistration.instance(
            client,
            "000000001",
            "Pedro",
            "Marmol",
            "Perez",
            "5522649498",
            "pedro@test.com",
            "1234",
            "external-token",
            "pedro.marmol",
            "SecurePass123#",
            SelfServiceRequestType.REGISTRATION,
            LocalDateTime.now().plusSeconds(30));

    assertEquals(client, reg.getClient());
    assertEquals("000000001", reg.getAccountNumber());
    assertEquals("Pedro", reg.getFirstName());
    assertEquals("Marmol", reg.getMiddleName());
    assertEquals("Perez", reg.getLastName());
    assertEquals("5522649498", reg.getMobileNumber());
    assertEquals("pedro@test.com", reg.getEmail());
    assertEquals("1234", reg.getAuthenticationToken());
    assertEquals("external-token", reg.getExternalAuthorizationToken());
    assertEquals("pedro.marmol", reg.getUsername());
    assertEquals("SecurePass123#", reg.getPassword());
    assertEquals(SelfServiceRequestType.REGISTRATION, reg.getRequestType());
    assertFalse(reg.isConsumed());
    assertNotNull(reg.getExpiresAt());
    assertNotNull(reg.getCreatedDate());
  }

  @Test
  void instance_shouldHandleNullMiddleName() {
    Client client = mock(Client.class);
    SelfServiceRegistration reg =
        SelfServiceRegistration.instance(
            client,
            "000000002",
            "John",
            null,
            "Doe",
            null,
            "john@test.com",
            "5678",
            "other-token",
            "john.doe",
            "Pass456#",
            SelfServiceRequestType.PASSWORD_RESET,
            LocalDateTime.now().plusSeconds(30));

    assertNull(reg.getMiddleName());
    assertNull(reg.getMobileNumber());
    assertEquals("John", reg.getFirstName());
  }

  @Test
  void defaultConstructor_shouldCreateEmptyInstance() {
    SelfServiceRegistration reg = new SelfServiceRegistration();
    assertNull(reg.getClient());
    assertNull(reg.getFirstName());
  }
}
