/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SelfServiceEnrollmentRequest {

  @Schema(example = "vilma", requiredMode = Schema.RequiredMode.REQUIRED)
  public String username;

  @Schema(example = "SecretPassword123#", requiredMode = Schema.RequiredMode.REQUIRED)
  public String password;

  @Schema(example = "Vilma")
  public String firstName;

  @Schema(example = "VILMA")
  public String firstname;

  @Schema(example = "PICAPIEDRA")
  public String middlename;

  @Schema(example = "Flintstone")
  public String lastName;

  @Schema(example = "PEREZ")
  public String lastname;

  /** Required when legalFormId = 2 (Entity / non-person). */
  @Schema(example = "APOLO CAPITAL LIMITADA")
  public String fullname;

  @Schema(example = "5522649498", requiredMode = Schema.RequiredMode.REQUIRED)
  public String mobileNumber;

  @Schema(example = "vilma@hotmail.com", requiredMode = Schema.RequiredMode.REQUIRED)
  public String email;

  @Schema(example = "email", requiredMode = Schema.RequiredMode.REQUIRED)
  public String authenticationMode;

  @Schema(example = "1", description = "1 = Person, 2 = Entity")
  public Long legalFormId;

  @Schema(example = "1")
  public Long officeId;

  @Schema(example = "false")
  public Boolean isStaff;

  @Schema(example = "17 febrero 2026")
  public String submittedOnDate;

  @Schema(example = "17 febrero 2026")
  public String activationDate;

  @Schema(example = "false")
  public Boolean active;

  @Schema(example = "ID12345")
  public String externalId;

  @Schema(example = "ID12345")
  public String externalID;

  @Schema(example = "dd MMMM yyyy")
  public String dateFormat;

  @Schema(example = "es")
  public String locale;

  @Schema(example = "2")
  public Long documentTypeId;

  @Schema(example = "111050918")
  public String documentKey;

  /** Entity-only block (legalFormId = 2). Passed through to Fineract createClient. */
  @Schema(description = "Non-person / entity details (constitution, incorp number, etc.)")
  public Map<String, Object> clientNonPersonDetails;

  public List<Map<String, Object>> familyMembers;

  public List<Map<String, Object>> datatables;

  public List<Map<String, Object>> address;
}