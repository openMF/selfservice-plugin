/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.apache.fineract.selfservice.security.service.TenantAwareJpaPlatformSelfServiceUserDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SelfServiceSecurityTestConfig.class)
@TestPropertySource("classpath:application-test.properties")
@WebAppConfiguration
class SelfServiceSecurityWiringTest {

  @Autowired private ApplicationContext ctx;

  @Qualifier("selfServiceAuthenticationProvider")
  @Autowired
  private DaoAuthenticationProvider selfServiceProvider;

  @Test
  void selfServiceAuthProvider_usesSelfServiceUserDetailsService() {
    UserDetailsService uds =
        (UserDetailsService)
            ReflectionTestUtils.getField(selfServiceProvider, "userDetailsService");
    assertInstanceOf(TenantAwareJpaPlatformSelfServiceUserDetailsService.class, uds);
  }

  @Test
  void selfServiceAuthProvider_isDistinctFromCoreProvider() {
    DaoAuthenticationProvider coreProvider =
        ctx.getBean("customAuthenticationProvider", DaoAuthenticationProvider.class);
    assertNotSame(coreProvider, selfServiceProvider);
  }

  @Test
  void coreAuthProvider_usesCoreUserDetailsService() {
    DaoAuthenticationProvider coreProvider =
        ctx.getBean("customAuthenticationProvider", DaoAuthenticationProvider.class);
    UserDetailsService uds =
        (UserDetailsService) ReflectionTestUtils.getField(coreProvider, "userDetailsService");
    assertInstanceOf(TenantAwareJpaPlatformUserDetailsService.class, uds);
  }

  @Test
  void selfServiceEntryPoint_exists() {
    assertNotNull(ctx.getBean("selfServiceBasicAuthenticationEntryPoint"));
  }

  @Test
  void selfServiceFilterChain_exists() {
    assertNotNull(ctx.getBean("selfServiceSecurityFilterChain"));
  }

  @Test
  void authResourceConstructor_hasQualifierAnnotation() throws NoSuchMethodException {
    java.lang.reflect.Constructor<?> ctor =
        org.apache.fineract.selfservice.security.api.SelfAuthenticationApiResource.class
            .getDeclaredConstructors()[0];
    java.lang.annotation.Annotation[][] paramAnnotations = ctor.getParameterAnnotations();
    boolean found = false;
    for (java.lang.annotation.Annotation ann : paramAnnotations[0]) {
      if (ann instanceof Qualifier q) {
        org.junit.jupiter.api.Assertions.assertEquals(
            "selfServiceAuthenticationProvider", q.value());
        found = true;
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        found,
        "@Qualifier annotation missing on constructor parameter. Check lombok.config has: lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier");
  }
}
