/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IpAddressMaskerTest {

  @ParameterizedTest
  @CsvSource({
    "203.0.113.9,203.0.x.x",
    "10.20.30.40,10.20.x.x",
    "192.168.1.1,192.168.x.x",
    "0.0.0.0,0.0.x.x",
    "255.255.255.255,255.255.x.x",
    "  203.0.113.9  ,203.0.x.x",
  })
  void ipv4_keepsOnlyTheFirstTwoOctets(final String raw, final String expected) {
    assertEquals(expected, IpAddressMasker.mask(raw));
  }

  @ParameterizedTest
  @CsvSource({
    "2001:db8:85a3::1,2001:db8:x:x:x:x:x:x",
    "2001:0db8:85a3:0000:0000:8a2e:0370:7334,2001:0db8:x:x:x:x:x:x",
    "2001:DB8::1,2001:db8:x:x:x:x:x:x",
  })
  void ipv6_keepsOnlyTheFirstTwoGroups(final String raw, final String expected) {
    assertEquals(expected, IpAddressMasker.mask(raw));
  }

  @Test
  void ipv6WithNothingIdentifyingAfterTheFirstGroup_givesNothing() {
    // "fe80::1%eth0" is a link-local address: the second group is empty
    assertNull(IpAddressMasker.mask("FE80::1%eth0"));
  }

  @Test
  void ipv4MappedIpv6_isTreatedAsIpv4() {
    assertEquals("203.0.x.x", IpAddressMasker.mask("::ffff:203.0.113.9"));
    assertEquals("203.0.x.x", IpAddressMasker.mask("::FFFF:203.0.113.9"));
  }

  @Test
  void aForwardedForList_usesTheFirstAddress() {
    assertEquals("203.0.x.x", IpAddressMasker.mask("203.0.113.9, 10.0.0.1, 10.0.0.2"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "Unknown",
        "unknown",
        "localhost",
        "example.com",
        "999.1.1.1",
        "1.2.3",
        "1.2.3.4.5",
        "::1",
        "::",
        "not an ip",
        "abc:def",
        "<script>alert(1)</script>",
        "203.0.113.9; DROP TABLE x"
      })
  void anythingThatIsNotAnIp_isNeverStored(final String raw) {
    assertNull(IpAddressMasker.mask(raw));
  }

  @Test
  void theFullAddressNeverSurvives() {
    final String masked = IpAddressMasker.mask("203.0.113.9");
    assertEquals(false, masked.contains("113"));
    assertEquals(false, masked.contains(".9"));
  }
}
