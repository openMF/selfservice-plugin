/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
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
