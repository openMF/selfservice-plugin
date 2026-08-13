/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.organisation.staff.service.StaffReadService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.data.AppSelfServiceUserData;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

class AppSelfServiceUserReadPlatformServiceImplTest {

  private PlatformSecurityContext platformSecurityContext;
  private JdbcTemplate jdbcTemplate;
  private AppSelfServiceUserRepository userRepository;
  private SelfServiceRoleReadPlatformService roleReadPlatformService;
  private AppSelfServiceUserReadPlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);

    PlatformSelfServiceSecurityContext selfServiceContext =
        mock(PlatformSelfServiceSecurityContext.class);
    platformSecurityContext = mock(PlatformSecurityContext.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    OfficeReadPlatformService officeReadPlatformService = mock(OfficeReadPlatformService.class);
    roleReadPlatformService = mock(SelfServiceRoleReadPlatformService.class);
    userRepository = mock(AppSelfServiceUserRepository.class);
    StaffReadService staffReadPlatformService = mock(StaffReadService.class);
    service =
        new AppSelfServiceUserReadPlatformServiceImpl(
            selfServiceContext,
            platformSecurityContext,
            jdbcTemplate,
            officeReadPlatformService,
            roleReadPlatformService,
            userRepository,
            staffReadPlatformService);
    when(roleReadPlatformService.retrieveAll()).thenReturn(new ArrayList<>());
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void retrieveSelfServiceUserForAdmin_shouldReturnStatusAndLinkedClients() throws Exception {
    AppSelfServiceUser user = user(10L, "reader", true, client(20L, "Client One"));
    when(platformSecurityContext.officeHierarchy()).thenReturn(".");
    when(userRepository.findByIdAndOfficeHierarchy(10L, ".%")).thenReturn(user);

    AppSelfServiceUserData result = service.retrieveSelfServiceUserForAdmin(10L);

    assertEquals(10L, result.getId());
    assertEquals("reader", field(result, "username"));
    assertTrue((Boolean) field(result, "enabled"));
    assertFalse((Boolean) field(result, "deleted"));
    Set<?> clients = (Set<?>) field(result, "clients");
    assertEquals(1, clients.size());
    Object client = clients.iterator().next();
    assertEquals(20L, field(client, "id"));
    assertEquals("Client One", field(client, "displayName"));
  }

  @Test
  void retrieveSelfServiceUserForAdmin_shouldReturnInactiveStatus() throws Exception {
    AppSelfServiceUser user = user(10L, "inactive", true);
    user.disable();
    when(platformSecurityContext.officeHierarchy()).thenReturn(".");
    when(userRepository.findByIdAndOfficeHierarchy(10L, ".%")).thenReturn(user);

    AppSelfServiceUserData result = service.retrieveSelfServiceUserForAdmin(10L);

    assertFalse((Boolean) field(result, "enabled"));
    assertFalse((Boolean) field(result, "deleted"));
  }

  @Test
  void retrieveSelfServiceUserForAdmin_shouldPreserveDeletedUserBehavior() {
    AppSelfServiceUser user = user(10L, "deleted", true);
    user.delete();
    when(platformSecurityContext.officeHierarchy()).thenReturn(".");
    when(userRepository.findByIdAndOfficeHierarchy(10L, ".%")).thenReturn(user);

    assertThrows(UserNotFoundException.class, () -> service.retrieveSelfServiceUserForAdmin(10L));
  }

  @Test
  void retrieveSelfServiceUserForAdmin_shouldRejectUserOutsideHierarchy() {
    when(platformSecurityContext.officeHierarchy()).thenReturn(".");
    when(userRepository.findByIdAndOfficeHierarchy(10L, ".%")).thenReturn(null);

    assertThrows(UserNotFoundException.class, () -> service.retrieveSelfServiceUserForAdmin(10L));
    verify(userRepository).findByIdAndOfficeHierarchy(10L, ".%");
  }

  @Test
  void retrieveAllSelfServiceUsersForAdmin_shouldUseAdminHierarchyAndAppendClients()
      throws Exception {
    AppUser admin = mock(AppUser.class);
    Office office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".");
    when(admin.getOffice()).thenReturn(office);
    when(platformSecurityContext.authenticatedUser()).thenReturn(admin);

    AppSelfServiceUserData row =
        AppSelfServiceUserData.adminInstance(
            10L,
            "reader",
            null,
            1L,
            "Head Office",
            "Read",
            null,
            "User",
            true,
            false,
            null,
            null,
            null,
            true,
            true);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(new Object[] {".%"})))
        .thenReturn(List.of(row));
    AppSelfServiceUser linkedUser = user(10L, "reader", true, client(20L, "Client One"));
    when(userRepository.findByIdIn(List.of(10L))).thenReturn(List.of(linkedUser));

    Collection<AppSelfServiceUserData> result = service.retrieveAllSelfServiceUsersForAdmin();

    AppSelfServiceUserData user = result.iterator().next();
    assertEquals(10L, user.getId());
    assertTrue((Boolean) field(user, "enabled"));
    assertFalse((Boolean) field(user, "deleted"));
    assertEquals(1, ((Set<?>) field(user, "clients")).size());
    verify(platformSecurityContext).authenticatedUser();
    verify(userRepository).findByIdIn(List.of(10L));
  }

  private AppSelfServiceUser user(
      Long id, String username, boolean selfService, Client... clients) {
    Office office = mock(Office.class);
    when(office.getId()).thenReturn(1L);
    when(office.getName()).thenReturn("Head Office");
    List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("DUMMY"));
    AppSelfServiceUser user =
        new AppSelfServiceUser(
            office,
            new User(username, "password", authorities),
            new HashSet<>(),
            username + "@example.com",
            "Test",
            "User",
            null,
            true,
            selfService,
            List.of(clients),
            false);
    setId(user, id);
    return user;
  }

  private Client client(Long id, String displayName) {
    Office office = mock(Office.class);
    when(office.getId()).thenReturn(1L);
    when(office.getName()).thenReturn("Head Office");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    when(client.getDisplayName()).thenReturn(displayName);
    when(client.getOffice()).thenReturn(office);
    return client;
  }

  private void setId(AppSelfServiceUser user, Long id) {
    try {
      Field idField = AppSelfServiceUser.class.getSuperclass().getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object field(Object target, String fieldName) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }
}
