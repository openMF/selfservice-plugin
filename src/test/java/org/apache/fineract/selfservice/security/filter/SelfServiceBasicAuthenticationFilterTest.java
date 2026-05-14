/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.filter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;

class SelfServiceBasicAuthenticationFilterTest {

  @Test
  void onSuccessfulAuthentication_doesNotThrow_withAppSelfServiceUserPrincipal() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    Authentication auth = mock(Authentication.class);
    AppSelfServiceUser selfServiceUser = mock(AppSelfServiceUser.class);
    when(auth.getPrincipal()).thenReturn(selfServiceUser);

    SelfServiceBasicAuthenticationFilter filter =
        new SelfServiceBasicAuthenticationFilter(
            mock(AuthenticationManager.class),
            mock(AuthenticationEntryPoint.class),
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatCode(() -> filter.onSuccessfulAuthentication(request, response, auth))
        .doesNotThrowAnyException();
  }
}
