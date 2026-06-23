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
package org.apache.fineract.selfservice.security.data;

import java.util.Collection;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.useradministration.data.RoleData;

/** Immutable data object for authentication. */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class SelfServiceAuthenticatedUserData {

  @SuppressWarnings("unused")
  private String username;

  @SuppressWarnings("unused")
  private Long userId;

  @SuppressWarnings("unused")
  private String base64EncodedAuthenticationKey;

  @SuppressWarnings("unused")
  private boolean authenticated;

  @SuppressWarnings("unused")
  private Long officeId;

  @SuppressWarnings("unused")
  private String officeName;

  @SuppressWarnings("unused")
  private Long staffId;

  @SuppressWarnings("unused")
  private String staffDisplayName;

  @SuppressWarnings("unused")
  private EnumOptionData organisationalRole;

  @SuppressWarnings("unused")
  private Collection<RoleData> roles;

  @SuppressWarnings("unused")
  private Collection<String> permissions;

  private Collection<Long> clients;

  @SuppressWarnings("unused")
  private boolean shouldRenewPassword;

  @SuppressWarnings("unused")
  private boolean isTwoFactorAuthenticationRequired;

  /**
   * The country name derived from the office address of the client's associated office. Empty
   * string when no country is available (fallback).
   */
  @SuppressWarnings("unused")
  private String country;

  @SuppressWarnings("unused")
  private SelfServiceAuthenticatedUserKycData kycValidations;

  // Add the new field
  private String refreshToken;

  // Add getter and setter (or rely on Lombok if applicable)
  public String getRefreshToken() {
    return refreshToken;
  }

  public SelfServiceAuthenticatedUserData setRefreshToken(String refreshToken) {
    this.refreshToken = refreshToken;
    return this;
  }
}
