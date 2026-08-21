/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.data;

import lombok.Data;

@Data
public class RemittanceRecipientRequest {

  private String firstName;
  private String lastName;
  private String middleName;
  private String motherMaidenName;
  private String dateOfBirth;
  private String email;
  private String phone;
  private Address address;
  private PrimaryDocument primaryDocument;

  @Data
  public static class Address {
    private String country;
    private String state;
    private String city;
    private String postalCode;
    private String line1;
  }

  @Data
  public static class PrimaryDocument {
    private String documentType;
    private String documentNumber;
    private String issuingCountry;
  }
}
