/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.stereotype.Component;

/**
 * Creates the first master user from configuration at startup.
 *
 * <p>A master context needs a way in before any master user exists, and no API can create the first
 * one without already requiring a master. Configuration is that way in: {@code
 * fineract.tenant-management.bootstrap-master-username} and {@code ...bootstrap-master-password},
 * typically supplied as environment variables.
 *
 * <p>The configured user is created once. If it already exists nothing is changed - in particular
 * its password is not reset - so a lingering or rotated environment variable can never silently
 * overwrite a master credential. Runs after the tenant-store migration that creates the table.
 */
@Component
@DependsOn("runTenantManagementTenantStoreMigrations")
@Slf4j
public class TenantMasterUserBootstrap implements InitializingBean {

  /**
   * Shortest bootstrap password accepted. A master user controls every tenant on the installation,
   * so a guessable one is refused outright rather than stored.
   */
  static final int MINIMUM_PASSWORD_LENGTH = 12;

  private final TenantMasterUserStore store;
  private final String username;
  private final String password;

  public TenantMasterUserBootstrap(
      final TenantMasterUserStore store,
      @Value("${fineract.tenant-management.bootstrap-master-username:}") final String username,
      @Value("${fineract.tenant-management.bootstrap-master-password:}") final String password) {
    this.store = store;
    this.username = username == null ? "" : username.trim();
    this.password = password == null ? "" : password;
  }

  @Override
  public void afterPropertiesSet() {
    if (username.isEmpty() || password.isEmpty()) {
      if (store.count() == 0) {
        log.warn(
            "No tenant management master user exists and none is configured; /v1/admin/tenants will"
                + " refuse every request until fineract.tenant-management.bootstrap-master-username"
                + " and bootstrap-master-password are set");
      }
      return;
    }

    if (store.findByUsername(username).isPresent()) {
      log.info("Master user [{}] already exists; bootstrap configuration left unapplied", username);
      return;
    }

    if (password.length() < MINIMUM_PASSWORD_LENGTH) {
      // Refused, not stored and not logged: the application still starts, but no master
      // user with a guessable password comes into existence.
      log.error(
          "Bootstrap password for master user [{}] is shorter than {} characters; no master user"
              + " was created",
          username,
          MINIMUM_PASSWORD_LENGTH);
      return;
    }

    try {
      store.create(
          username,
          PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password),
          TenantMasterAccess.SUPER_MASTER_ROLE);
    } catch (final DuplicateKeyException e) {
      // Nodes starting together on a first deployment can all find the user absent and all
      // try to insert it; the unique username lets exactly one succeed. Losing that race is
      // success once the user is confirmed to exist - failing startup over it would stop
      // every node but the winner.
      if (store.findByUsername(username).isEmpty()) {
        throw e;
      }
      log.info("Master user [{}] was created concurrently by another node", username);
      return;
    }
    log.info("Created tenant management master user [{}]", username);
  }
}
