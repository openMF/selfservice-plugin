/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Masks an IP address before it is stored or shown: the full address is personal data and the user
 * only needs to recognise "where from".
 *
 * <ul>
 *   <li>IPv4 {@code 203.0.113.9} becomes {@code 203.0.x.x} (first two octets kept).
 *   <li>IPv6 {@code 2001:db8:85a3::1} becomes {@code 2001:db8:x:x:x:x:x:x} (first two groups).
 *   <li>IPv4-mapped IPv6 ({@code ::ffff:203.0.113.9}) is treated as IPv4.
 *   <li>A list such as an {@code X-Forwarded-For} value uses the first address.
 *   <li>Anything that is not an IP address, or is blank / "Unknown", gives {@code null}: nothing
 *       that could be a hostname, a token or a typo is ever stored.
 * </ul>
 */
public final class IpAddressMasker {

  private static final Pattern IPV4 =
      Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
  private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-f:]+");

  private IpAddressMasker() {}

  /** The masked address, or {@code null} when there is nothing valid to mask. */
  public static String mask(final String raw) {
    if (raw == null) {
      return null;
    }
    String ip = raw.trim();
    final int comma = ip.indexOf(',');
    if (comma >= 0) {
      ip = ip.substring(0, comma).trim();
    }
    if (ip.isEmpty()) {
      return null;
    }
    final String lower = ip.toLowerCase(Locale.ROOT);
    if (lower.startsWith("::ffff:") && IPV4.matcher(lower.substring(7)).matches()) {
      return maskIpv4(lower.substring(7));
    }
    if (IPV4.matcher(ip).matches()) {
      return maskIpv4(ip);
    }
    return maskIpv6(lower);
  }

  private static String maskIpv4(final String ip) {
    final String[] octets = ip.split("\\.");
    return octets[0] + "." + octets[1] + ".x.x";
  }

  private static String maskIpv6(final String ip) {
    final int zone = ip.indexOf('%');
    final String address = zone >= 0 ? ip.substring(0, zone) : ip;
    if (!address.contains(":") || !IPV6_CHARS.matcher(address).matches()) {
      return null;
    }
    final String[] groups = address.split(":", -1);
    if (groups.length < 3 || groups.length > 8 + 1) {
      return null;
    }
    final String first = groups[0];
    final String second = groups[1];
    if (first.isEmpty() || second.isEmpty() || first.length() > 4 || second.length() > 4) {
      // "::1", "::" and similar: there is nothing identifying to keep
      return null;
    }
    return first + ":" + second + ":x:x:x:x:x:x";
  }
}
