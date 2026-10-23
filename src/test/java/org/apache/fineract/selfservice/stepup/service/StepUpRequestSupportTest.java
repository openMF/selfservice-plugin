/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StepUpRequestSupportTest {

  @Test
  void clientIp_isTheAddressOfTheConnection() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");

    assertEquals("198.51.100.7", StepUpRequestSupport.clientIp(request));
  }

  @Test
  void clientIp_ignoresHeadersAClientCanForge() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");
    request.addHeader("X-Forwarded-For", "203.0.113.9");
    request.addHeader("X-Real-Ip", "203.0.113.10");

    assertEquals("198.51.100.7", StepUpRequestSupport.clientIp(request));
  }

  @Test
  void clientIp_withoutARequest_isNull() {
    assertNull(StepUpRequestSupport.clientIp(null));
  }

  @Test
  void clientIp_isShortenedToTheColumnSize() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("x".repeat(100));

    assertEquals(45, StepUpRequestSupport.clientIp(request).length());
  }
}
