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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.junit.jupiter.api.Test;

class StepUpSettingsTest {

  @Test
  void defaults_haveStepUpDisabledAndSafeLimits() {
    final StepUpSettings s = StepUpSettings.defaults();
    assertFalse(s.enabled());
    assertEquals(6, s.otpLength());
    assertEquals(300, s.otpTtlSeconds());
    assertEquals(5, s.maxAttempts());
    assertEquals(900, s.lockoutSeconds());
    assertEquals(60, s.resendCooldownSeconds());
    assertEquals(3, s.maxResends());
    assertEquals(120, s.grantTtlSeconds());
    assertEquals(
        EnumSet.of(
            StepUpAction.TPT_TRANSFER, StepUpAction.CONFIRM_TRANSFER, StepUpAction.CHANGE_PASSWORD),
        s.protectedActions());
    assertEquals(EnumSet.allOf(StepUpChannel.class), s.channels());
    assertFalse(s.invalidateSessionsOnPasswordChange());
  }

  @Test
  void disabled_meansNothingIsProtected() {
    final StepUpSettings s = StepUpSettings.defaults();
    for (final StepUpAction action : StepUpAction.values()) {
      assertFalse(s.isProtected(action));
    }
  }

  @Test
  void enabled_protectsOnlyTheConfiguredActions() {
    final StepUpSettings s =
        StepUpSettings.fromProperties(
            Map.of("enabled", "true", "protectedActions", "tpt_transfer, OWN_TRANSFER ,bogus"));
    assertTrue(s.isProtected(StepUpAction.TPT_TRANSFER));
    assertTrue(s.isProtected(StepUpAction.OWN_TRANSFER));
    assertFalse(s.isProtected(StepUpAction.CHANGE_PASSWORD));
    assertFalse(s.isProtected(StepUpAction.CONFIRM_TRANSFER));
  }

  @Test
  void explicitlyEmptyProtectedActions_protectsNothing() {
    final StepUpSettings s =
        StepUpSettings.fromProperties(Map.of("enabled", "true", "protectedActions", ""));
    assertTrue(s.protectedActions().isEmpty());
    assertFalse(s.isProtected(StepUpAction.TPT_TRANSFER));
  }

  @Test
  void enabledFlag_isStrict() {
    assertTrue(StepUpSettings.fromProperties(Map.of("enabled", "TRUE")).enabled());
    assertFalse(StepUpSettings.fromProperties(Map.of("enabled", "yes")).enabled());
    assertFalse(StepUpSettings.fromProperties(Map.of("enabled", "1")).enabled());
    assertFalse(StepUpSettings.fromProperties(Map.of("enabled", "")).enabled());
  }

  @Test
  void outOfRangeOrMalformedNumbers_fallBackToDefaults() {
    final Map<String, String> props = new HashMap<>();
    props.put("otpLength", "3"); // below 4
    props.put("otpTtlSeconds", "99999"); // above 900
    props.put("maxAttempts", "0");
    props.put("lockoutSeconds", "abc");
    props.put("resendCooldownSeconds", "-5");
    props.put("grantTtlSeconds", "5");
    final StepUpSettings s = StepUpSettings.fromProperties(props);
    assertEquals(6, s.otpLength());
    assertEquals(300, s.otpTtlSeconds());
    assertEquals(5, s.maxAttempts());
    assertEquals(900, s.lockoutSeconds());
    assertEquals(60, s.resendCooldownSeconds());
    assertEquals(120, s.grantTtlSeconds());
  }

  @Test
  void validNumbers_areApplied() {
    final Map<String, String> props = new HashMap<>();
    props.put("otpLength", "8");
    props.put("otpTtlSeconds", "120");
    props.put("maxAttempts", "3");
    props.put("lockoutSeconds", "600");
    props.put("resendCooldownSeconds", "30");
    props.put("maxResends", "0");
    props.put("grantTtlSeconds", "60");
    final StepUpSettings s = StepUpSettings.fromProperties(props);
    assertEquals(8, s.otpLength());
    assertEquals(120, s.otpTtlSeconds());
    assertEquals(3, s.maxAttempts());
    assertEquals(600, s.lockoutSeconds());
    assertEquals(30, s.resendCooldownSeconds());
    assertEquals(0, s.maxResends());
    assertEquals(60, s.grantTtlSeconds());
  }

  @Test
  void channels_areParsedAndUnknownOnesIgnored() {
    final StepUpSettings s = StepUpSettings.fromProperties(Map.of("channels", "sms, whatsapp"));
    assertEquals(EnumSet.of(StepUpChannel.SMS), s.channels());
    assertTrue(s.isChannelAllowed(StepUpChannel.SMS));
    assertFalse(s.isChannelAllowed(StepUpChannel.EMAIL));
  }

  @Test
  void sessionInvalidationOnPasswordChange_defaultsToEnabledFlagButCanBeOverridden() {
    assertTrue(
        StepUpSettings.fromProperties(Map.of("enabled", "true"))
            .invalidateSessionsOnPasswordChange());
    assertFalse(
        StepUpSettings.fromProperties(
                Map.of("enabled", "true", "invalidateSessionsOnPasswordChange", "false"))
            .invalidateSessionsOnPasswordChange());
    assertTrue(
        StepUpSettings.fromProperties(Map.of("invalidateSessionsOnPasswordChange", "true"))
            .invalidateSessionsOnPasswordChange());
  }

  @Test
  void hmacSecret_isReadPerTenantFromTheProperties() {
    assertEquals(
        "a-long-secret-a-long-secret-a-long-1",
        StepUpSettings.fromProperties(Map.of("hmacSecret", "a-long-secret-a-long-secret-a-long-1"))
            .hmacSecret());
    assertNull(StepUpSettings.fromProperties(Map.of()).hmacSecret());
    assertNull(StepUpSettings.fromProperties(Map.of("hmacSecret", "  ")).hmacSecret());
  }

  @Test
  void toString_neverPrintsTheSecret() {
    final String text =
        StepUpSettings.fromProperties(Map.of("hmacSecret", "top-secret-value-top-secret-value-12"))
            .toString();

    assertFalse(text.contains("top-secret"));
    assertTrue(text.contains("hmacSecret=set"));
    assertTrue(StepUpSettings.defaults().toString().contains("hmacSecret=not set"));
  }
}
