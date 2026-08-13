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
package org.apache.fineract.selfservice.useradministration.data;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.organisation.staff.data.StaffData;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.useradministration.data.RoleData;

/** Immutable data object for application user data. */
public final class AppSelfServiceUserData {

  private final Long id;
  private final String username;
  private final Long officeId;
  private final String officeName;
  private final String firstname;
  private final String middleName;
  private final String lastname;
  private final String email;
  private final Boolean enabled;
  private final Boolean deleted;
  private final Boolean passwordNeverExpires;

  // import fields
  private List<Long> roles;
  private Boolean sendPasswordToEmail;
  private Long staffId;
  private transient Integer rowIndex;

  @SuppressWarnings("unused")
  private final Collection<OfficeData> allowedOffices;

  private final Collection<RoleData> availableRoles;
  private final Collection<RoleData> selfServiceRoles;
  private final Collection<RoleData> selectedRoles;
  private final StaffData staff;
  private final Boolean isSelfServiceUser;

  @SuppressWarnings("unused")
  private Set<ClientData> clients;

  public static AppSelfServiceUserData importInstance(
      Long officeId,
      Long staffId,
      String username,
      String firstname,
      String lastname,
      String email,
      Boolean sendPasswordToEmail,
      Boolean passwordNeverExpires,
      List<Long> roleIds,
      Integer rowIndex) {
    return new AppSelfServiceUserData(
        officeId,
        staffId,
        username,
        firstname,
        lastname,
        email,
        sendPasswordToEmail,
        passwordNeverExpires,
        roleIds,
        rowIndex);
  }

  private AppSelfServiceUserData(
      Long officeId,
      Long staffId,
      String username,
      String firstname,
      String lastname,
      String email,
      Boolean sendPasswordToEmail,
      Boolean passwordNeverExpires,
      List<Long> roleIds,
      Integer rowIndex) {
    this.id = null;
    this.username = username;
    this.officeId = officeId;
    this.officeName = null;
    this.firstname = firstname;
    this.middleName = null;
    this.lastname = lastname;
    this.email = email;
    this.enabled = null;
    this.deleted = null;
    this.passwordNeverExpires = passwordNeverExpires;
    this.roles = roleIds;
    this.sendPasswordToEmail = sendPasswordToEmail;
    this.staffId = staffId;
    this.rowIndex = rowIndex;
    this.allowedOffices = null;
    this.availableRoles = null;
    this.selfServiceRoles = null;
    this.selectedRoles = null;
    this.staff = null;
    this.isSelfServiceUser = null;
    this.clients = null;
  }

  public Integer getRowIndex() {
    return rowIndex;
  }

  public static AppSelfServiceUserData template(
      final AppSelfServiceUserData user, final Collection<OfficeData> officesForDropdown) {
    return new AppSelfServiceUserData(
        user.id,
        user.username,
        user.email,
        user.officeId,
        user.officeName,
        user.firstname,
        user.middleName,
        user.lastname,
        user.enabled,
        user.deleted,
        user.availableRoles,
        user.selfServiceRoles,
        user.selectedRoles,
        officesForDropdown,
        user.staff,
        user.passwordNeverExpires,
        user.isSelfServiceUser);
  }

  public static AppSelfServiceUserData template(
      final Collection<OfficeData> offices,
      final Collection<RoleData> availableRoles,
      final Collection<RoleData> selfServiceRoles) {
    return new AppSelfServiceUserData(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        availableRoles,
        selfServiceRoles,
        null,
        offices,
        null,
        null,
        null);
  }

  public static AppSelfServiceUserData dropdown(final Long id, final String username) {
    return new AppSelfServiceUserData(
        id, username, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null);
  }

  public static AppSelfServiceUserData instance(
      final Long id,
      final String username,
      final String email,
      final Long officeId,
      final String officeName,
      final String firstname,
      final String lastname,
      final Collection<RoleData> availableRoles,
      final Collection<RoleData> selfServiceRoles,
      final Collection<RoleData> selectedRoles,
      final StaffData staff,
      final Boolean passwordNeverExpire,
      final Boolean isSelfServiceUser) {
    return new AppSelfServiceUserData(
        id,
        username,
        email,
        officeId,
        officeName,
        firstname,
        null,
        lastname,
        null,
        null,
        availableRoles,
        selfServiceRoles,
        selectedRoles,
        null,
        staff,
        passwordNeverExpire,
        isSelfServiceUser);
  }

  private AppSelfServiceUserData(
      final Long id,
      final String username,
      final String email,
      final Long officeId,
      final String officeName,
      final String firstname,
      final String middleName,
      final String lastname,
      final Boolean enabled,
      final Boolean deleted,
      final Collection<RoleData> availableRoles,
      final Collection<RoleData> selfServiceRoles,
      final Collection<RoleData> selectedRoles,
      final Collection<OfficeData> allowedOffices,
      final StaffData staff,
      final Boolean passwordNeverExpire,
      final Boolean isSelfServiceUser) {
    this.id = id;
    this.username = username;
    this.officeId = officeId;
    this.officeName = officeName;
    this.firstname = firstname;
    this.middleName = middleName;
    this.lastname = lastname;
    this.email = email;
    this.enabled = enabled;
    this.deleted = deleted;
    this.allowedOffices = allowedOffices;
    this.availableRoles = availableRoles;
    this.selfServiceRoles = selfServiceRoles;
    this.selectedRoles = selectedRoles;
    this.staff = staff;
    this.passwordNeverExpires = passwordNeverExpire;
    this.isSelfServiceUser = isSelfServiceUser;
  }

  public static AppSelfServiceUserData adminInstance(
      final Long id,
      final String username,
      final String email,
      final Long officeId,
      final String officeName,
      final String firstname,
      final String middleName,
      final String lastname,
      final Boolean enabled,
      final Boolean deleted,
      final Collection<RoleData> availableRoles,
      final Collection<RoleData> selectedRoles,
      final StaffData staff,
      final Boolean passwordNeverExpire,
      final Boolean isSelfServiceUser) {
    return new AppSelfServiceUserData(
        id,
        username,
        email,
        officeId,
        officeName,
        firstname,
        middleName,
        lastname,
        enabled,
        deleted,
        availableRoles,
        null,
        selectedRoles,
        null,
        staff,
        passwordNeverExpire,
        isSelfServiceUser);
  }

  public boolean hasIdentifyOf(final Long createdById) {
    return this.id.equals(createdById);
  }

  public Long getId() {
    return this.id;
  }

  public String username() {
    return this.username;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !(o instanceof AppSelfServiceUserData)) {
      return false;
    }

    AppSelfServiceUserData that = (AppSelfServiceUserData) o;

    if (id != null ? !id.equals(that.id) : that.id != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : 0;
  }

  public void setClients(Set<ClientData> clients) {
    this.clients = clients;
  }

  public boolean isSelfServiceUser() {
    return this.isSelfServiceUser == null ? false : this.isSelfServiceUser;
  }
}
