/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.data;

/** Immutable data transfer object representing core office identification fields. */
public final class OfficeDetailsData {

  private final Long id;
  private final String name;
  private final String externalId;

  private OfficeDetailsData(final Long id, final String name, final String externalId) {
    this.id = id;
    this.name = name;
    this.externalId = externalId;
  }

  /**
   * Creates a new instance.
   *
   * @param id the office identifier
   * @param name the office name
   * @param externalId the external identifier assigned to the office
   * @return a new {@code OfficeDetailsData}
   */
  public static OfficeDetailsData instance(
      final Long id, final String name, final String externalId) {
    return new OfficeDetailsData(id, name, externalId);
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getExternalId() {
    return externalId;
  }
}
