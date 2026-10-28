/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.junit.jupiter.api.Test;

class StepUpContactTest {

  private static StepUpContact contact(final String email, final String mobile) {
    return new StepUpContact(1L, "user", "Ana", "Perez", email, mobile);
  }

  @Test
  void supports_onlyChannelsWithADestination() {
    final StepUpContact emailOnly = contact("ana@example.com", null);
    assertTrue(emailOnly.supports(StepUpChannel.EMAIL));
    assertFalse(emailOnly.supports(StepUpChannel.SMS));

    final StepUpContact smsOnly = contact(null, "5519500324");
    assertTrue(smsOnly.supports(StepUpChannel.SMS));
    assertFalse(smsOnly.supports(StepUpChannel.EMAIL));
  }

  @Test
  void maskedEmail_keepsFirstLetterAndDomainOnly() {
    assertEquals(
        "a***@example.com",
        contact("ana@example.com", null).maskedDestination(StepUpChannel.EMAIL));
    assertEquals("***", StepUpContact.maskEmail("not-an-email"));
  }

  @Test
  void maskedMobile_keepsOnlyLastFourDigits() {
    assertEquals("******0324", contact(null, "5519500324").maskedDestination(StepUpChannel.SMS));
    assertEquals(
        "******0324", contact(null, "+52 55 1950 0324").maskedDestination(StepUpChannel.SMS));
    assertEquals("****", StepUpContact.maskMobile("1234"));
  }

  @Test
  void theAuthenticatorApp_hasNoDestination_soNothingIsEverSentToIt() {
    final StepUpContact both = contact("ana@example.com", "5519500324");
    assertNull(both.destination(StepUpChannel.TOTP));
    assertNull(both.maskedDestination(StepUpChannel.TOTP));
    assertFalse(both.supports(StepUpChannel.TOTP));
  }

  @Test
  void maskedDestination_isNullWhenMissing() {
    assertNull(contact(null, null).maskedDestination(StepUpChannel.EMAIL));
    assertNull(contact(null, null).maskedDestination(StepUpChannel.SMS));
  }
}
