/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.totp;

/** RFC 4648 Base32 (upper case, no padding): the alphabet authenticator apps expect for keys. */
public final class Base32 {

  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private Base32() {}

  public static String encode(final byte[] data) {
    final StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (final byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
    }
    return out.toString();
  }

  /** Decodes upper or lower case input; spaces, dashes and padding are ignored. */
  public static byte[] decode(final String text) {
    final String clean = text.replaceAll("[\s=-]", "").toUpperCase(java.util.Locale.ROOT);
    final byte[] out = new byte[clean.length() * 5 / 8];
    int buffer = 0;
    int bits = 0;
    int index = 0;
    for (int i = 0; i < clean.length(); i++) {
      final int value = valueOf(clean.charAt(i));
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
        bits -= 8;
      }
    }
    return out;
  }

  private static int valueOf(final char c) {
    if (c >= 'A' && c <= 'Z') {
      return c - 'A';
    }
    if (c >= '2' && c <= '7') {
      return c - '2' + 26;
    }
    throw new IllegalArgumentException("Not a Base32 character");
  }
}
