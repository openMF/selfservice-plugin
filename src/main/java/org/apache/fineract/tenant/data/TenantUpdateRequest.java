/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.data;

/**
 * A validated request to update a tenant.
 *
 * <p>Every field is optional and a null means "leave unchanged", so a client may send only what it
 * is actually changing. The optional {@code description}, {@code contactEmail} and {@code
 * schemaConnectionParameters} are cleared with an empty string; the other fields cannot be blank.
 * {@code identifier} is absent by design: it is how every request selects a tenant and is embedded
 * in that tenant's existing sessions and integrations, so renaming one through this API would
 * strand them. A tenant that needs a different identifier is created anew.
 *
 * @param name new name, or null to leave unchanged
 * @param timezoneId new IANA zone identifier, or null
 * @param description new free text, null to leave unchanged, or empty to clear
 * @param contactEmail new administrative contact, null to leave unchanged, or empty to clear
 * @param schemaServer new host, or null
 * @param schemaServerPort new port, or null
 * @param schemaUsername new user, or null
 * @param schemaPassword new password, or null to keep the stored one
 * @param schemaConnectionParameters new JDBC parameters, null to leave unchanged, or empty to clear
 * @param autoUpdate new auto-migrate flag, or null
 */
public record TenantUpdateRequest(
    String name,
    String timezoneId,
    String description,
    String contactEmail,
    String schemaServer,
    String schemaServerPort,
    String schemaUsername,
    String schemaPassword,
    String schemaConnectionParameters,
    Boolean autoUpdate) {

  /**
   * Names the fields this request changes, for the audit trail.
   *
   * <p>Names only - never values. {@code schemaPassword} appears here when a password was rotated,
   * so the rotation is recorded while the password itself never leaves this object.
   *
   * @return the changed field names, in a stable order
   */
  public java.util.List<String> changedFieldNames() {
    final java.util.List<String> changed = new java.util.ArrayList<>();
    if (name != null) {
      changed.add("name");
    }
    if (timezoneId != null) {
      changed.add("timezoneId");
    }
    if (description != null) {
      changed.add("description");
    }
    if (contactEmail != null) {
      changed.add("contactEmail");
    }
    if (schemaServer != null) {
      changed.add("schemaServer");
    }
    if (schemaServerPort != null) {
      changed.add("schemaServerPort");
    }
    if (schemaUsername != null) {
      changed.add("schemaUsername");
    }
    if (schemaPassword != null) {
      changed.add("schemaPassword");
    }
    if (schemaConnectionParameters != null) {
      changed.add("schemaConnectionParameters");
    }
    if (autoUpdate != null) {
      changed.add("autoUpdate");
    }
    return changed;
  }

  /**
   * @return true when the request would change nothing, so the caller can be told its request was
   *     empty rather than silently getting back an unchanged tenant
   */
  public boolean isEmpty() {
    return name == null
        && timezoneId == null
        && description == null
        && contactEmail == null
        && schemaServer == null
        && schemaServerPort == null
        && schemaUsername == null
        && schemaPassword == null
        && schemaConnectionParameters == null
        && autoUpdate == null;
  }
}
