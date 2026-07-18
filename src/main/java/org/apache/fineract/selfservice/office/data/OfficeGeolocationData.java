/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.data;

import java.math.BigDecimal;

/** Immutable data transfer object representing an office's geographic coordinates. */
public final class OfficeGeolocationData {

  private final BigDecimal latitude;
  private final BigDecimal longitude;

  private OfficeGeolocationData(final BigDecimal latitude, final BigDecimal longitude) {
    this.latitude = latitude;
    this.longitude = longitude;
  }

  /**
   * Creates a new instance.
   *
   * @param latitude the latitude in decimal degrees
   * @param longitude the longitude in decimal degrees
   * @return a new {@code OfficeGeolocationData}
   */
  public static OfficeGeolocationData instance(
      final BigDecimal latitude, final BigDecimal longitude) {
    return new OfficeGeolocationData(latitude, longitude);
  }

  public BigDecimal getLatitude() {
    return latitude;
  }

  public BigDecimal getLongitude() {
    return longitude;
  }
}
