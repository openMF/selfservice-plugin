/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.client.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AppSelfServiceUserClientMapperReadServiceImplTest {

  private static final Long CLIENT_ID = 10L;
  private static final Long USER_ID = 7L;

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private PlatformSelfServiceSecurityContext context;

  private AppSelfServiceUserClientMapperReadServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AppSelfServiceUserClientMapperReadServiceImpl(jdbcTemplate, context);
  }

  @Test
  void isClientMappedReturnsTrueWhenMappingExists() {
    givenMapping(true);

    assertTrue(service.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID));
  }

  @Test
  void isClientMappedReturnsFalseWhenMappingDoesNotExist() {
    givenMapping(false);

    assertFalse(service.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID));
  }

  @Test
  void isClientMappedQueriesMappingTableWithClientIdBeforeUserId() {
    givenMapping(true);

    service.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID);

    verify(jdbcTemplate)
        .queryForObject(
            contains("m_selfservice_user_client_mapping"),
            eq(Boolean.class),
            eq(CLIENT_ID),
            eq(USER_ID));
  }

  @Test
  void validateThrowsClientNotFoundWhenClientIdIsNull() {
    assertThrows(
        ClientNotFoundException.class,
        () -> service.validateAppSelfServiceUserClientsMapping(null));

    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void validateThrowsClientNotFoundWhenClientIsNotMappedToUser() {
    givenAuthenticatedUser();
    givenMapping(false);

    assertThrows(
        ClientNotFoundException.class,
        () -> service.validateAppSelfServiceUserClientsMapping(CLIENT_ID));
  }

  @Test
  void validatePassesWhenClientIsMappedToUser() {
    givenAuthenticatedUser();
    givenMapping(true);

    assertDoesNotThrow(() -> service.validateAppSelfServiceUserClientsMapping(CLIENT_ID));
  }

  private void givenMapping(final boolean mapped) {
    when(jdbcTemplate.queryForObject(
            anyString(), eq(Boolean.class), eq(CLIENT_ID), eq(USER_ID)))
        .thenReturn(mapped);
  }

  private void givenAuthenticatedUser() {
    final AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getId()).thenReturn(USER_ID);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }
}
