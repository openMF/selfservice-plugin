/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.pockets.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.selfservice.pockets.data.PocketAccountMappingData;
import org.apache.fineract.selfservice.pockets.domain.PocketAccountMapping;
import org.apache.fineract.selfservice.pockets.domain.PocketAccountMappingRepositoryWrapper;
import org.apache.fineract.selfservice.pockets.domain.PocketRepositoryWrapper;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class PocketAccountMappingReadPlatformServiceImplTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private PocketRepositoryWrapper pocketRepositoryWrapper;
  @Mock private PocketAccountMappingRepositoryWrapper pocketAccountMappingRepositoryWrapper;
  @Mock private AppSelfServiceUser user;

  private PocketAccountMappingReadPlatformServiceImpl service;

  private static final Long USER_ID = 7L;
  private static final Long POCKET_ID = 42L;

  @BeforeEach
  void setUp() {
    service =
        new PocketAccountMappingReadPlatformServiceImpl(
            jdbcTemplate, context, pocketRepositoryWrapper, pocketAccountMappingRepositoryWrapper);
  }

  private void mockAuthenticatedUser() {
    when(user.getId()).thenReturn(USER_ID);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }

  @Test
  void retrieveAll_noPocket_returnsNull() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserId(USER_ID)).thenReturn(null);

    assertNull(service.retrieveAll());
  }

  @Test
  void retrieveAll_pocketWithNoMappings_returnsNull() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserId(USER_ID)).thenReturn(POCKET_ID);
    when(pocketAccountMappingRepositoryWrapper.findByPocketId(POCKET_ID)).thenReturn(List.of());

    assertNull(service.retrieveAll());
  }

  @Test
  void retrieveAll_groupsMappingsByAccountType() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserId(USER_ID)).thenReturn(POCKET_ID);
    PocketAccountMapping loan =
        PocketAccountMapping.instance(POCKET_ID, 1L, EntityAccountType.LOAN.getValue(), "L1");
    PocketAccountMapping savings =
        PocketAccountMapping.instance(POCKET_ID, 2L, EntityAccountType.SAVINGS.getValue(), "S1");
    PocketAccountMapping shares =
        PocketAccountMapping.instance(POCKET_ID, 3L, EntityAccountType.SHARES.getValue(), "SH1");
    when(pocketAccountMappingRepositoryWrapper.findByPocketId(POCKET_ID))
        .thenReturn(List.of(loan, savings, shares));

    PocketAccountMappingData data = service.retrieveAll();

    assertEquals(1, data.getLoanAccounts().size());
    assertEquals(1, data.getSavingsAccounts().size());
    assertEquals(1, data.getShareAccounts().size());
    assertEquals(1L, data.getLoanAccounts().iterator().next().getAccountId().longValue());
    assertEquals(2L, data.getSavingsAccounts().iterator().next().getAccountId().longValue());
    assertEquals(3L, data.getShareAccounts().iterator().next().getAccountId().longValue());
  }

  @Test
  void validatePocketAndAccountMapping_positiveCount_returnsTrue() {
    when(jdbcTemplate.queryForObject(
            anyString(),
            eq(Integer.class),
            eq(POCKET_ID),
            eq(1L),
            eq(EntityAccountType.LOAN.getValue())))
        .thenReturn(1);

    assertTrue(
        service.validatePocketAndAccountMapping(POCKET_ID, 1L, EntityAccountType.LOAN.getValue()));
  }

  @Test
  void validatePocketAndAccountMapping_zeroCount_returnsFalse() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
        .thenReturn(0);

    assertFalse(
        service.validatePocketAndAccountMapping(POCKET_ID, 1L, EntityAccountType.LOAN.getValue()));
  }

  @Test
  void validatePocketAndAccountMapping_nullCount_returnsFalse() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
        .thenReturn(null);

    assertFalse(
        service.validatePocketAndAccountMapping(POCKET_ID, 1L, EntityAccountType.LOAN.getValue()));
  }
}
