/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The crypto bean is created by Spring, which a mock based test cannot notice. A bean with several
 * constructors and no {@code @Autowired} one would fail at startup ("No default constructor
 * found"). Also proves that the key comes from the tenant settings, not from the environment.
 */
class StepUpCryptoWiringTest {

  private static AnnotationConfigApplicationContext context(final StepUpSettingsProvider provider) {
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(StepUpSettingsProvider.class, () -> provider);
    context.register(StepUpCrypto.class);
    context.refresh();
    return context;
  }

  private static StepUpSettingsProvider providerWith(final Map<String, String> properties) {
    final StepUpSettingsProvider provider = mock(StepUpSettingsProvider.class);
    when(provider.current()).thenReturn(StepUpSettings.fromProperties(properties));
    return provider;
  }

  @Test
  void springCreatesTheBean_andReadsTheSecretFromTheTenantSettings() {
    try (AnnotationConfigApplicationContext context =
        context(providerWith(Map.of("hmacSecret", "wiring-test-secret-wiring-test-secret-12")))) {
      final StepUpCrypto crypto = context.getBean(StepUpCrypto.class);
      assertTrue(crypto.hasUsableSecret());
      assertTrue(crypto.otpMatches("123456", "salt", crypto.hashOtp("123456", "salt")));
    }
  }

  @Test
  void withoutATenantSecret_theBeanStillStarts_butHasNoUsableSecret() {
    try (AnnotationConfigApplicationContext context = context(providerWith(Map.of()))) {
      assertFalse(context.getBean(StepUpCrypto.class).hasUsableSecret());
    }
  }

  @Test
  void thePlaceholderSeededByLiquibaseIsNotUsable() {
    try (AnnotationConfigApplicationContext context =
        context(providerWith(Map.of("hmacSecret", "change-me-before-enabling")))) {
      assertFalse(context.getBean(StepUpCrypto.class).hasUsableSecret());
    }
  }

  @Test
  void aSystemPropertyOrEnvironmentVariableIsNotUsedAnyMore() {
    System.setProperty("mifos.self.service.stepup.hmac-secret", "x".repeat(40));
    try (AnnotationConfigApplicationContext context = context(providerWith(Map.of()))) {
      assertFalse(context.getBean(StepUpCrypto.class).hasUsableSecret());
    } finally {
      System.clearProperty("mifos.self.service.stepup.hmac-secret");
    }
  }

  @Test
  void eachTenantHasItsOwnKey_evenInTheSameBean() {
    final StepUpSettingsProvider provider = mock(StepUpSettingsProvider.class);
    when(provider.current())
        .thenReturn(
            StepUpSettings.fromProperties(
                Map.of("hmacSecret", "tenant-a-secret-tenant-a-secret-123456")))
        .thenReturn(
            StepUpSettings.fromProperties(
                Map.of("hmacSecret", "tenant-b-secret-tenant-b-secret-123456")));
    try (AnnotationConfigApplicationContext context = context(provider)) {
      final StepUpCrypto crypto = context.getBean(StepUpCrypto.class);
      final String asTenantA = crypto.hashOtp("123456", "salt");
      final String asTenantB = crypto.hashOtp("123456", "salt");
      assertNotEquals(asTenantA, asTenantB);
    }
  }
}
