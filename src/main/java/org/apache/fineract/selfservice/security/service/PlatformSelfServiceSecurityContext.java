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

import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.infrastructure.security.service.PlatformUserRightsContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.useradministration.domain.AppUser;

public interface PlatformSelfServiceSecurityContext extends PlatformUserRightsContext {

  AppSelfServiceUser authenticatedSelfServiceUser();

  /**
   * Convenience method returns null (does not throw an exception) if an authenticated user is not
   * present
   *
   * <p>To be used only in service layer methods that can be triggered via both the API and batch
   * Jobs (which do not have an authenticated user)
   *
   * @return
   */
  AppSelfServiceUser getAuthenticatedUserIfPresent();

  void validateAccessRights(String resourceOfficeHierarchy);

  String officeHierarchy();

  boolean doesPasswordHasToBeRenewed(AppSelfServiceUser currentUser);

  AppSelfServiceUser authenticatedUser(CommandWrapper commandWrapper);

  /**
   * Validates that the authenticated self-service user has read permission for the given resource
   * type, using core Fineract's permission model via {@link AppUser#validateHasReadPermission}.
   *
   * @param resourceType the resource type (e.g. "LOANPRODUCT", "SAVINGSPRODUCT")
   * @throws org.apache.fineract.infrastructure.security.exception.NoAuthorizationException if the
   *     user lacks the permission
   */
  void validateHasReadPermission(String resourceType);

  /**
   * Validates that the authenticated self-service user has create permission for the given resource
   * type, using core Fineract's permission model via {@link AppUser#validateHasCreatePermission}.
   */
  void validateHasCreatePermission(String resourceType);

  /**
   * Validates that the authenticated self-service user has delete permission for the given resource
   * type, using core Fineract's permission model via {@link AppUser#validateHasDeletePermission}.
   */
  void validateHasDeletePermission(String resourceType);
}
