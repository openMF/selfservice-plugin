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

/** Immutable data transfer object representing an office's physical address. */
public final class SelfOfficeAddressData {

  private final String streetAndNumber;
  private final String postalCode;
  private final String municipality;
  private final String state;
  private final String country;

  private SelfOfficeAddressData(
      final String streetAndNumber,
      final String postalCode,
      final String municipality,
      final String state,
      final String country) {
    this.streetAndNumber = streetAndNumber;
    this.postalCode = postalCode;
    this.municipality = municipality;
    this.state = state;
    this.country = country;
  }

  /**
   * Creates a new instance.
   *
   * @param streetAndNumber the street name and building number
   * @param postalCode the postal or ZIP code
   * @param municipality the city or municipality
   * @param state the state or province
   * @param country the country name
   * @return a new {@code SelfOfficeAddressData}
   */
  public static SelfOfficeAddressData instance(
      final String streetAndNumber,
      final String postalCode,
      final String municipality,
      final String state,
      final String country) {
    return new SelfOfficeAddressData(streetAndNumber, postalCode, municipality, state, country);
  }

  public String getStreetAndNumber() {
    return streetAndNumber;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public String getMunicipality() {
    return municipality;
  }

  public String getState() {
    return state;
  }

  public String getCountry() {
    return country;
  }
}
