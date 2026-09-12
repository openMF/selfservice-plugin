/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.domain;

import java.util.regex.Pattern;

/**
 * The one definition of an acceptable tenant schema name.
 *
 * <p>A schema name is concatenated into {@code CREATE DATABASE} DDL, because no JDBC driver lets an
 * identifier be bound as a parameter. The pattern - not escaping - is what makes that safe, so the
 * request validator and the provisioning service must never disagree about it; both read it from
 * here.
 *
 * <p>The name must start with a letter or underscore: PostgreSQL rejects an unquoted identifier
 * that starts with a digit ({@code CREATE DATABASE 123tenant} is a syntax error). It is capped at
 * 63 characters, PostgreSQL's identifier limit and the shortest across the engines Fineract
 * supports, so a name accepted here is creatable on any of them.
 */
public final class TenantSchemaName {

  public static final Pattern PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

  private TenantSchemaName() {}

  /**
   * @param schemaName candidate name, possibly null
   * @return true when the name is safe to place, unquoted, into DDL on every supported engine
   */
  public static boolean isValid(final String schemaName) {
    return schemaName != null && PATTERN.matcher(schemaName).matches();
  }
}
