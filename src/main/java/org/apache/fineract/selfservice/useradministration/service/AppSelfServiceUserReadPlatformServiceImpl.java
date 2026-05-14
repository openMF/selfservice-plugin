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
package org.apache.fineract.selfservice.useradministration.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.organisation.staff.data.StaffData;
import org.apache.fineract.organisation.staff.service.StaffReadService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.data.AppSelfServiceUserData;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.data.RoleData;
import org.apache.fineract.useradministration.domain.Role;
import org.apache.fineract.useradministration.exception.UserNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AppSelfServiceUserReadPlatformServiceImpl
    implements AppSelfServiceUserReadPlatformService {

  private final PlatformSelfServiceSecurityContext context;
  private final JdbcTemplate jdbcTemplate;
  private final OfficeReadPlatformService officeReadPlatformService;
  private final SelfServiceRoleReadPlatformService roleReadPlatformService;
  private final AppSelfServiceUserRepository appUserRepository;
  private final StaffReadService staffReadPlatformService;

  /*
   * used for caching in spring expression language.
   */
  public PlatformSelfServiceSecurityContext getContext() {
    return this.context;
  }

  @Override
  @Cacheable(
      value = "users",
      key =
          "T(org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil).getTenant().getTenantIdentifier().concat(#root.target.context.authenticatedUser().getOffice().getHierarchy())")
  public Collection<AppSelfServiceUserData> retrieveAllSelfServiceUsers() {

    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();
    final String hierarchy = currentUser.getOffice().getHierarchy();
    final String hierarchySearchString = hierarchy + "%";

    final AppSelfServiceUserMapper mapper =
        new AppSelfServiceUserMapper(this.roleReadPlatformService, this.staffReadPlatformService);
    final String sql = "select " + mapper.schema();

    return this.jdbcTemplate.query(sql, mapper, new Object[] {hierarchySearchString}); // NOSONAR
  }

  @Override
  public Collection<AppSelfServiceUserData> retrieveSearchTemplate() {
    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();
    final String hierarchy = currentUser.getOffice().getHierarchy();
    final String hierarchySearchString = hierarchy + "%";

    final AppSelfServiceUserLookupMapper mapper = new AppSelfServiceUserLookupMapper();
    final String sql = "select " + mapper.schema();

    return this.jdbcTemplate.query(sql, mapper, new Object[] {hierarchySearchString}); // NOSONAR
  }

  @Override
  public AppSelfServiceUserData retrieveNewSelfServiceUserDetails() {

    final Collection<OfficeData> offices =
        this.officeReadPlatformService.retrieveAllOfficesForDropdown();
    final Collection<RoleData> availableRoles =
        this.roleReadPlatformService.retrieveAllActiveRoles();
    final Collection<RoleData> selfServiceRoles =
        this.roleReadPlatformService.retrieveAllSelfServiceRoles();

    return AppSelfServiceUserData.template(offices, availableRoles, selfServiceRoles);
  }

  @Override
  public AppSelfServiceUserData retrieveSelfServiceUser(final Long userId) {

    this.context.authenticatedSelfServiceUser();

    final AppSelfServiceUser user =
        this.appUserRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    if (user.isDeleted()) {
      throw new UserNotFoundException(userId);
    }

    final Collection<RoleData> availableRoles = this.roleReadPlatformService.retrieveAll();

    final Collection<RoleData> selectedUserRoles = new ArrayList<>();
    final Set<Role> userRoles = user.getRoles();
    for (final Role role : userRoles) {
      selectedUserRoles.add(role.toData());
    }

    availableRoles.removeAll(selectedUserRoles);

    final StaffData linkedStaff;
    if (user.getStaff() != null) {
      linkedStaff = this.staffReadPlatformService.retrieveStaff(user.getStaffId());
    } else {
      linkedStaff = null;
    }

    AppSelfServiceUserData retUser =
        AppSelfServiceUserData.instance(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getOffice().getId(),
            user.getOffice().getName(),
            user.getFirstname(),
            user.getLastname(),
            availableRoles,
            null,
            selectedUserRoles,
            linkedStaff,
            user.getPasswordNeverExpires(),
            user.isSelfServiceUser());

    if (retUser.isSelfServiceUser()) {
      Set<ClientData> clients = new HashSet<>();
      for (AppSelfServiceUserClientMapping clientMap : user.getAppUserClientMappings()) {
        Client client = clientMap.getClient();
        clients.add(
            ClientData.lookup(
                client.getId(),
                client.getDisplayName(),
                client.getOffice().getId(),
                client.getOffice().getName()));
      }
      retUser.setClients(clients);
    }

    return retUser;
  }

  private static final class AppSelfServiceUserMapper implements RowMapper<AppSelfServiceUserData> {

    private final SelfServiceRoleReadPlatformService roleReadPlatformService;
    private final StaffReadService staffReadPlatformService;

    AppSelfServiceUserMapper(
        final SelfServiceRoleReadPlatformService roleReadPlatformService,
        final StaffReadService staffReadPlatformService) {
      this.roleReadPlatformService = roleReadPlatformService;
      this.staffReadPlatformService = staffReadPlatformService;
    }

    @Override
    public AppSelfServiceUserData mapRow(
        final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {

      final Long id = rs.getLong("id");
      final String username = rs.getString("username");
      final String firstname = rs.getString("firstname");
      final String lastname = rs.getString("lastname");
      final String email = rs.getString("email");
      final Long officeId = JdbcSupport.getLong(rs, "officeId");
      final String officeName = rs.getString("officeName");
      final Long staffId = JdbcSupport.getLong(rs, "staffId");
      final Boolean passwordNeverExpire = rs.getBoolean("passwordNeverExpires");
      final Boolean isSelfServiceUser = rs.getBoolean("isSelfServiceUser");
      final Collection<RoleData> selectedRoles =
          this.roleReadPlatformService.retrieveAppUserRoles(id);

      final StaffData linkedStaff;
      if (staffId != null) {
        linkedStaff = this.staffReadPlatformService.retrieveStaff(staffId);
      } else {
        linkedStaff = null;
      }
      return AppSelfServiceUserData.instance(
          id,
          username,
          email,
          officeId,
          officeName,
          firstname,
          lastname,
          null,
          null,
          selectedRoles,
          linkedStaff,
          passwordNeverExpire,
          isSelfServiceUser);
    }

    public String schema() {
      return " u.id as id, u.username as username, u.firstname as firstname, u.lastname as lastname, u.email as email, u.password_never_expires as passwordNeverExpires, "
          + " u.office_id as officeId, o.name as officeName, u.staff_id as staffId, u.is_self_service_user as isSelfServiceUser from m_appselfservice_user u "
          + " join m_office o on o.id = u.office_id where o.hierarchy like ? and u.is_deleted=false order by u.username";
    }
  }

  private static final class AppSelfServiceUserLookupMapper
      implements RowMapper<AppSelfServiceUserData> {

    @Override
    public AppSelfServiceUserData mapRow(
        final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {

      final Long id = rs.getLong("id");
      final String username = rs.getString("username");

      return AppSelfServiceUserData.dropdown(id, username);
    }

    public String schema() {
      return " u.id as id, u.username as username from m_appselfservice_user u "
          + " join m_office o on o.id = u.office_id where o.hierarchy like ? and u.is_deleted=false order by u.username";
    }
  }

  @Override
  public boolean isUsernameExist(String username) {
    String sql = "select count(*) from m_appselfservice_user where username = ?";
    Object[] params = new Object[] {username};
    int count = this.jdbcTemplate.queryForObject(sql, Integer.class, params);
    return count != 0;
  }
}
