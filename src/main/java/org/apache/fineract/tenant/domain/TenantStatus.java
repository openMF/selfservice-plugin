/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lifecycle state of a tenant in the central registry.
 *
 * <p>Apache Fineract has no such concept: a row in {@code tenants} exists and the tenant is
 * reachable, or it does not exist at all. MX-406 requires the three states below, so they are
 * introduced by this plugin along with the {@code status} column that stores them.
 *
 * <p>Stored as the enum name rather than an ordinal, so inserting a state later cannot silently
 * change what existing rows mean.
 */
public enum TenantStatus {

  /** Fully operational. The only state in which a tenant serves requests. */
  ACTIVE,

  /** Deliberately taken out of service, for example a tenant that has been wound down. */
  INACTIVE,

  /** Temporarily withheld, for example pending payment or investigation. */
  SUSPENDED;

  /**
   * Parses a status supplied by a client.
   *
   * <p>Matching is case insensitive for the caller's convenience but pinned to {@link Locale#ROOT},
   * because the tenant's locale must not decide whether a status is recognised: under a Turkish
   * locale the default folding turns the {@code I} of {@code INACTIVE} into a dotless {@code ı},
   * which would match nothing.
   *
   * @param value status as supplied, possibly null or blank
   * @return the matching status, or empty when the value names none
   */
  public static Optional<TenantStatus> fromString(final String value) {
    if (value == null) {
      return Optional.empty();
    }
    final String candidate = value.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values()).filter(status -> status.name().equals(candidate)).findFirst();
  }

  /**
   * @return every status name, in declaration order, for the API template and error messages
   */
  public static List<String> names() {
    return Arrays.stream(values()).map(TenantStatus::name).toList();
  }
}
