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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

class SelfServiceUserAdminWritePlatformServiceImplTest {

  private AppSelfServiceUserRepository userRepository;
  private AppSelfServiceUserClientMappingRepository mappingRepository;
  private ClientRepositoryWrapper clientRepositoryWrapper;
  private AppSelfServiceUserReadPlatformService readPlatformService;
  private PlatformSecurityContext context;
  private SelfServiceUserAdminWritePlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    FineractPlatformTenant tenant =
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.now().minusDays(1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);

    userRepository = mock(AppSelfServiceUserRepository.class);
    mappingRepository = mock(AppSelfServiceUserClientMappingRepository.class);
    clientRepositoryWrapper = mock(ClientRepositoryWrapper.class);
    readPlatformService = mock(AppSelfServiceUserReadPlatformService.class);
    context = mock(PlatformSecurityContext.class);
    when(context.officeHierarchy()).thenReturn(".");
    service =
        new SelfServiceUserAdminWritePlatformServiceImpl(
            userRepository,
            mappingRepository,
            clientRepositoryWrapper,
            readPlatformService,
            context);
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void activate_shouldEnableExistingUser() {
    AppSelfServiceUser user = user(10L);
    user.disable();
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);

    service.activate(10L);

    assertTrue(user.isEnabled());
    verify(userRepository).saveAndFlush(user);
  }

  @Test
  void inactivate_shouldDisableExistingUser() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);

    service.inactivate(10L);

    assertFalse(user.isEnabled());
    assertFalse(user.isDeleted());
    verify(userRepository).saveAndFlush(user);
  }

  @Test
  void linkClient_shouldRejectDuplicateUserClientLink() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(true);

    assertThrows(PlatformDataIntegrityException.class, () -> service.linkClient(10L, 20L));
    verify(mappingRepository).existsByAppUserIdAndClientId(10L, 20L);
    verifyNoMoreInteractions(mappingRepository);
  }

  @Test
  void linkClient_shouldRejectClientAlreadyLinkedToAnotherUser() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(false);
    when(mappingRepository.existsByClientId(20L)).thenReturn(true);

    assertThrows(PlatformDataIntegrityException.class, () -> service.linkClient(10L, 20L));
  }

  @Test
  void linkClient_shouldCreateMappingWhenValid() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(false);
    when(mappingRepository.existsByClientId(20L)).thenReturn(false);

    service.linkClient(10L, 20L);

    verify(clientRepositoryWrapper).getClientByClientIdAndHierarchy(20L, ".%");
    verify(mappingRepository).saveClientUserMapping(10L, 20L);
  }

  @Test
  void linkClient_shouldTranslateDuplicateUserClientConstraintViolation() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(false);
    when(mappingRepository.existsByClientId(20L)).thenReturn(false);
    whenSaveMappingFails("Duplicate entry for key 'appuser_id_client_id'");

    assertThrows(PlatformDataIntegrityException.class, () -> service.linkClient(10L, 20L));
  }

  @Test
  void linkClient_shouldTranslateClientAlreadyLinkedConstraintViolation() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(false);
    when(mappingRepository.existsByClientId(20L)).thenReturn(false);
    whenSaveMappingFails("Duplicate entry for key 'unique_self_client'");

    assertThrows(PlatformDataIntegrityException.class, () -> service.linkClient(10L, 20L));
  }

  @Test
  void delinkClient_shouldRejectMissingMapping() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(false);

    assertThrows(PlatformDataIntegrityException.class, () -> service.delinkClient(10L, 20L));
  }

  @Test
  void delinkClient_shouldDeleteExistingMapping() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);
    when(clientRepositoryWrapper.getClientByClientIdAndHierarchy(20L, ".%"))
        .thenReturn(mock(Client.class));
    when(mappingRepository.existsByAppUserIdAndClientId(10L, 20L)).thenReturn(true);

    service.delinkClient(10L, 20L);

    verify(clientRepositoryWrapper).getClientByClientIdAndHierarchy(20L, ".%");
    verify(mappingRepository).deleteByAppUserIdAndClientId(10L, 20L);
  }

  @Test
  void delete_shouldSoftDeleteAndReleaseUsername() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L)).thenReturn(user);

    service.delete(10L);

    assertTrue(user.isDeleted());
    assertFalse(user.isEnabled());
    assertEquals("10_DELETED_testuser", user.getUsername());
    verify(userRepository).saveAndFlush(user);
  }

  @Test
  void activate_shouldRejectUserOutsideHierarchy() {
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L))
        .thenThrow(new UserNotFoundException(10L));

    assertThrows(UserNotFoundException.class, () -> service.activate(10L));
    verifyNoInteractions(userRepository);
  }

  @Test
  void inactivate_shouldRejectUserOutsideHierarchy() {
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L))
        .thenThrow(new UserNotFoundException(10L));

    assertThrows(UserNotFoundException.class, () -> service.inactivate(10L));
    verifyNoInteractions(userRepository);
  }

  @Test
  void linkClient_shouldRejectUserOutsideHierarchyBeforeClientLookup() {
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L))
        .thenThrow(new UserNotFoundException(10L));

    assertThrows(UserNotFoundException.class, () -> service.linkClient(10L, 20L));
    verifyNoInteractions(clientRepositoryWrapper, mappingRepository);
  }

  @Test
  void delinkClient_shouldRejectUserOutsideHierarchyBeforeClientLookup() {
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L))
        .thenThrow(new UserNotFoundException(10L));

    assertThrows(UserNotFoundException.class, () -> service.delinkClient(10L, 20L));
    verifyNoInteractions(clientRepositoryWrapper, mappingRepository);
  }

  @Test
  void delete_shouldRejectUserOutsideHierarchy() {
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L))
        .thenThrow(new UserNotFoundException(10L));

    assertThrows(UserNotFoundException.class, () -> service.delete(10L));
    verifyNoInteractions(userRepository);
  }

  @Test
  void delete_shouldRejectRepeatedDeleteWithoutRenamingAgain() {
    AppSelfServiceUser user = user(10L);
    when(readPlatformService.retrieveSelfServiceUserDomainForAdmin(10L))
        .thenReturn(user)
        .thenThrow(new UserNotFoundException(10L));

    service.delete(10L);
    String deletedUsername = user.getUsername();

    assertThrows(UserNotFoundException.class, () -> service.delete(10L));
    assertEquals(deletedUsername, user.getUsername());
    verify(userRepository).saveAndFlush(user);
  }

  private AppSelfServiceUser user(Long id) {
    Office office = mock(Office.class);
    ArrayList<SimpleGrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("DUMMY"));
    AppSelfServiceUser user =
        new AppSelfServiceUser(
            office,
            new User("testuser", "password", authorities),
            new HashSet<>(),
            "test@example.com",
            "Test",
            "User",
            null,
            true,
            true,
            new ArrayList<>(),
            false);
    setId(user, id);
    return user;
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

  private void whenSaveMappingFails(String message) {
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException(message))
        .when(mappingRepository)
        .saveClientUserMapping(10L, 20L);
  }
}
