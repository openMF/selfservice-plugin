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
package org.apache.fineract.selfservice.security.service;

import java.lang.reflect.Field;
import java.util.Collection;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.security.domain.PlatformSelfServiceUser;
import org.apache.fineract.selfservice.security.domain.PlatformSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceRoleReadPlatformService;
import org.apache.fineract.useradministration.data.RoleData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Used in securityContext.xml as implementation of spring security's {@link UserDetailsService}.
 */
@Service("selfServiceUserDetailsService")
public class TenantAwareJpaPlatformSelfServiceUserDetailsService
    implements PlatformSelfServiceUserDetailsService {

  @Autowired private PlatformSelfServiceUserRepository platformUserRepository;
  @Autowired private SelfServiceRoleReadPlatformService roleReadPlatformService;

  @Override
  public UserDetails loadUserByUsername(final String username)
      throws UsernameNotFoundException, DataAccessException {

    // Retrieve active users only
    final boolean deleted = false;
    final boolean enabled = true;

    final PlatformSelfServiceUser appUser =
        this.platformUserRepository.findByUsernameAndDeletedAndEnabled(username, deleted, enabled);

    if (appUser == null) {
      throw new UsernameNotFoundException(username + ": not found");
    }

    if (appUser instanceof AppSelfServiceUser ssUser) {
      if (!ssUser.isSelfServiceUser()) {
        throw new UsernameNotFoundException(username + ": not a self-service user");
      }

      final Collection<RoleData> roles =
          this.roleReadPlatformService.retrieveAppUserRoles(ssUser.getId());
      final boolean hasEnabledSelfServiceRole =
          roles.stream()
              .anyMatch(
                  r ->
                      SelfServiceApiConstants.SELF_SERVICE_USER_ROLE.equals(r.getName())
                          && !isRoleDisabled(r));

      if (!hasEnabledSelfServiceRole) {
        throw new UsernameNotFoundException(username + ": self-service role missing or disabled");
      }
    }

    return appUser;
  }

  /**
   * RoleData currently does not expose a disabled getter, but it carries the value as a field. Use
   * reflection to enforce role-enabled checks without modifying the core Fineract model.
   */
  private static boolean isRoleDisabled(RoleData roleData) {
    try {
      Field f = RoleData.class.getDeclaredField("disabled");
      f.setAccessible(true);
      Object v = f.get(roleData);
      return Boolean.TRUE.equals(v);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      // Fail closed: if we cannot determine role status, deny authentication.
      return true;
    }
  }
}
