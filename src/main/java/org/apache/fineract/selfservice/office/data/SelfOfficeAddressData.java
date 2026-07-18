/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
