/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.security;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads and creates tenant administration master users in the tenant store.
 *
 * <p>Deliberately not a Spring {@code UserDetailsService} bean. Fineract registers its own user
 * details services and authentication manager, and a second bean of those types could be injected
 * by type where the platform expects its own. {@link TenantManagementSecurityConfiguration} adapts
 * this store for its chain alone.
 */
@Component
public class TenantMasterUserStore {

  /**
   * A master user as stored.
   *
   * @param username login name, unique across the installation
   * @param passwordHash Spring Security delegating hash, never a clear password
   * @param role the role granted, {@link TenantMasterAccess#SUPER_MASTER_ROLE} today
   * @param enabled whether the user may authenticate
   */
  public record MasterUser(String username, String passwordHash, String role, boolean enabled) {}

  private final JdbcTemplate jdbcTemplate;

  public TenantMasterUserStore(
      @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource) {
    this.jdbcTemplate = new JdbcTemplate(tenantStoreDataSource);
  }

  /**
   * @param username login name as supplied, possibly null
   * @return the user, or empty when none has that name
   */
  public Optional<MasterUser> findByUsername(final String username) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    final List<MasterUser> users =
        jdbcTemplate.query(
            "select username, password_hash, role, enabled from tenant_master_user"
                + " where username = ?",
            (rs, rowNum) ->
                new MasterUser(
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("role"),
                    rs.getBoolean("enabled")),
            username);
    return users.stream().findFirst();
  }

  /**
   * @return how many master users exist
   */
  public long count() {
    final Long count =
        jdbcTemplate.queryForObject("select count(*) from tenant_master_user", Long.class);
    return count == null ? 0 : count;
  }

  /**
   * Stores a new master user.
   *
   * @param passwordHash an already-encoded hash; this method never sees a clear password
   */
  public void create(final String username, final String passwordHash, final String role) {
    jdbcTemplate.update(
        "insert into tenant_master_user (username, password_hash, role, enabled, created_at)"
            + " values (?, ?, ?, ?, ?)",
        username,
        passwordHash,
        role,
        Boolean.TRUE,
        // Zone-less column, written as a UTC wall-clock value like every other registry
        // timestamp this feature writes.
        LocalDateTime.now(ZoneOffset.UTC));
  }
}
