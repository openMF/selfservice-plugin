/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.fineract.tenant.data.TenantConnectionData;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps a row of the tenant registry onto {@link TenantData}.
 *
 * <p>Joins to the tenant's read-write connection only. {@code tenants} carries two connection
 * references, {@code oltp_id} and {@code report_id}, and Fineract's own {@code TenantMapper}
 * selects between them the same way. Administration edits the operational connection, so this
 * mapper follows {@code oltp_id}.
 *
 * <p>No password column is selected. Credentials are write-only across this feature, so they are
 * left out of the projection entirely rather than read and then dropped - a column that is never
 * fetched cannot be serialised by accident later.
 */
public final class TenantRowMapper implements RowMapper<TenantData> {

  /**
   * Projection and joins shared by the list, count and single-tenant queries, so the three cannot
   * drift apart. Callers append their own {@code WHERE} and {@code ORDER BY}.
   */
  public static final String SELECT_SCHEMA =
      " t.id as id, t.identifier as identifier, t.name as name, t.timezone_id as timezoneId,"
          + " t.status as status, t.description as description, t.contact_email as contactEmail,"
          + " t.joined_date as joinedDate, t.created_date as createdDate, t.lastmodified_date as"
          + " lastModifiedDate, ts.id as connectionId, ts.schema_name as schemaName,"
          + " ts.schema_server as schemaServer, ts.schema_server_port as schemaServerPort,"
          + " ts.schema_username as schemaUsername, ts.schema_connection_parameters as"
          + " schemaConnectionParameters, ts.auto_update as autoUpdate from tenants t left join"
          + " tenant_server_connections ts on t.oltp_id = ts.id ";

  @Override
  public TenantData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
    return new TenantData(
        rs.getLong("id"),
        rs.getString("identifier"),
        rs.getString("name"),
        rs.getString("timezoneId"),
        // Null for a value this enum does not name - a hand edit or corruption. Reporting it
        // as ACTIVE would show administrators the opposite of what the status filter does
        // (it refuses such a tenant), so the API says plainly that the status is not
        // recognised. Not thrown either: one bad row must not fail the whole listing.
        TenantStatus.fromString(rs.getString("status")).orElse(null),
        rs.getString("description"),
        rs.getString("contactEmail"),
        rs.getObject("joinedDate", LocalDate.class),
        toUtc(rs.getObject("createdDate", LocalDateTime.class)),
        toUtc(rs.getObject("lastModifiedDate", LocalDateTime.class)),
        mapConnection(rs));
  }

  /**
   * @return the tenant's connection, or null when the outer join matched no row - possible because
   *     the join is a left join, so a registry in an inconsistent state still lists its tenants
   *     instead of failing outright
   */
  private TenantConnectionData mapConnection(final ResultSet rs) throws SQLException {
    final long connectionId = rs.getLong("connectionId");
    if (rs.wasNull()) {
      return null;
    }
    return new TenantConnectionData(
        connectionId,
        rs.getString("schemaName"),
        rs.getString("schemaServer"),
        rs.getString("schemaServerPort"),
        rs.getString("schemaUsername"),
        rs.getString("schemaConnectionParameters"),
        rs.getBoolean("autoUpdate"));
  }

  /**
   * Attaches UTC to a zone-less registry timestamp.
   *
   * <p>Read as a {@link LocalDateTime} rather than through {@code getTimestamp}: that overload
   * interprets the stored wall-clock value in the JVM's default time zone, so the same row would
   * read as different instants on servers configured differently. Fineract does not force the JVM
   * into UTC, and this feature writes these columns as UTC wall-clock values.
   */
  private static OffsetDateTime toUtc(final LocalDateTime value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}
