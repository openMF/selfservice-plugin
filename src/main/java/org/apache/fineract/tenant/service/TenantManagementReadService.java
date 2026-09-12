/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.data.TenantTemplateData;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.exception.TenantNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads the central tenant registry.
 *
 * <p><strong>Why JDBC rather than JPA.</strong> The registry lives in the tenant store database,
 * reached through the {@code hikariTenantDataSource} bean, which is a different datasource from the
 * per-tenant {@code routingDataSource} this plugin's entities are mapped against. A Spring Data
 * repository would be bound to the tenant's own schema and would not find these tables at all, so
 * the registry is queried directly - the same approach Fineract core takes in {@code
 * JdbcTenantDetailsService}.
 */
@Service
public class TenantManagementReadService {

  /**
   * Largest page this endpoint will return.
   *
   * <p>Caps an unbounded {@code limit} so one request cannot pull an entire registry into memory,
   * and so a client that omits the parameter gets a page rather than everything.
   */
  static final int MAX_PAGE_SIZE = 200;

  private final JdbcTemplate jdbcTemplate;

  public TenantManagementReadService(
      @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {
    this.jdbcTemplate = new JdbcTemplate(tenantStoreDataSource);
  }

  /**
   * Lists tenants, newest registry entries last.
   *
   * @param search matched case insensitively against identifier and name, ignored when blank
   * @param status restricts to one lifecycle state, ignored when null
   * @param offset rows to skip, treated as 0 when null or negative
   * @param limit rows to return, clamped to {@link #MAX_PAGE_SIZE}
   * @return the matching page together with the total number of matches
   */
  public Page<TenantData> retrieveAll(
      final String search, final TenantStatus status, final Integer offset, final Integer limit) {

    final StringBuilder where = new StringBuilder(" where 1 = 1 ");
    final List<Object> arguments = new ArrayList<>();

    if (search != null && !search.isBlank()) {
      // Bound as a parameter rather than concatenated, so the term - which comes
      // straight off a query string - cannot alter the statement.
      //
      // Binding alone does NOT make the term literal: LIKE still reads % and _ inside
      // a bound value as wildcards, so a search for "%" would match every tenant. The
      // term is therefore escaped as well, which is what makes the search mean what
      // the user typed.
      where.append(
          " and (lower(t.identifier) like ? escape '!' or lower(t.name) like ? escape '!') ");
      final String term = "%" + escapeLikeWildcards(search.trim().toLowerCase(Locale.ROOT)) + "%";
      arguments.add(term);
      arguments.add(term);
    }
    if (status != null) {
      where.append(" and t.status = ? ");
      arguments.add(status.name());
    }

    final Integer total =
        this.jdbcTemplate.queryForObject(
            "select count(*) from tenants t " + where, Integer.class, arguments.toArray());

    final int pageSize =
        limit == null || limit <= 0 ? MAX_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
    final int rowsToSkip = offset == null || offset < 0 ? 0 : offset;

    final List<Object> pagedArguments = new ArrayList<>(arguments);
    pagedArguments.add(pageSize);
    pagedArguments.add(rowsToSkip);

    final List<TenantData> tenants =
        this.jdbcTemplate.query(
            "select " + TenantRowMapper.SELECT_SCHEMA + where + " order by t.id limit ? offset ?",
            new TenantRowMapper(),
            pagedArguments.toArray());

    return new Page<>(tenants, total == null ? tenants.size() : total);
  }

  /**
   * Escapes the characters {@code LIKE} treats as wildcards, so a search matches literally.
   *
   * <p>{@code !} is used as the escape character rather than the more usual backslash: MySQL
   * additionally treats a backslash as an escape inside string literals, so a backslash-escaped
   * pattern has to be written differently there than on PostgreSQL. {@code !} has no special
   * meaning to either, so one expression works on both.
   *
   * <p>The escape character itself is escaped first, or escaping {@code %} would then re-escape the
   * {@code !} that had just been introduced.
   */
  private static String escapeLikeWildcards(final String term) {
    return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /**
   * @param id primary key in {@code tenants}
   * @return the tenant
   * @throws TenantNotFoundException when no tenant has that id
   */
  public TenantData retrieveOne(final Long id) {
    try {
      return this.jdbcTemplate.queryForObject(
          "select " + TenantRowMapper.SELECT_SCHEMA + " where t.id = ?", new TenantRowMapper(), id);
    } catch (final EmptyResultDataAccessException e) {
      throw new TenantNotFoundException(id);
    }
  }

  /**
   * @param identifier tenant identifier, compared exactly
   * @return true when a tenant already holds this identifier
   */
  public boolean existsByIdentifier(final String identifier) {
    final Integer count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from tenants where identifier = ?", Integer.class, identifier);
    return count != null && count > 0;
  }

  /**
   * @return the options an administration client needs to build its create and edit forms
   */
  public TenantTemplateData retrieveTemplate() {
    return new TenantTemplateData(retrieveTimezones(), TenantStatus.names());
  }

  /**
   * Lists selectable time zones.
   *
   * <p>Prefers the registry's own {@code timezones} table, so an installation that has curated that
   * list keeps control of what administrators may choose. That table ships empty in some
   * deployments, so an empty result falls back to the zones this JVM knows - which is what the
   * platform ultimately resolves a tenant's zone against anyway. Falling back beats returning an
   * empty picker the UI cannot complete a form with.
   */
  private List<String> retrieveTimezones() {
    final List<String> configured =
        this.jdbcTemplate.queryForList(
            "select timezonename from timezones order by timezonename", String.class);
    if (!configured.isEmpty()) {
      return configured;
    }
    return ZoneId.getAvailableZoneIds().stream().sorted().toList();
  }
}
