/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * {@link StepUpCrypto} has a second, package-private constructor for tests. Spring must still pick
 * the public {@code Environment} one; a missing {@code @Autowired} made the whole Fineract context
 * fail at startup ("No default constructor found"), which no mock based test can notice.
 */
class StepUpCryptoWiringTest {

  private static AnnotationConfigApplicationContext context(final Map<String, Object> properties) {
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", properties));
    context.register(StepUpCrypto.class);
    context.refresh();
    return context;
  }

  @Test
  void springCreatesTheBean_andReadsTheSecretFromTheEnvironment() {
    try (AnnotationConfigApplicationContext context =
        context(Map.of(StepUpCrypto.SECRET_PROPERTY, "wiring-test-secret-wiring-test-secret-12"))) {
      final StepUpCrypto crypto = context.getBean(StepUpCrypto.class);
      assertTrue(crypto.hasUsableSecret());
      assertTrue(crypto.otpMatches("123456", "salt", crypto.hashOtp("123456", "salt")));
    }
  }

  @Test
  void withoutTheProperty_theBeanStillStarts_butHasNoUsableSecret() {
    try (AnnotationConfigApplicationContext context = context(Map.of())) {
      assertFalse(context.getBean(StepUpCrypto.class).hasUsableSecret());
    }
  }
}
