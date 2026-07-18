/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.pockets.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.pockets.data.PocketDataValidator;
import org.apache.fineract.selfservice.pockets.domain.Pocket;
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

@ExtendWith(MockitoExtension.class)
class PocketWritePlatformServiceImplTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private PocketDataValidator pocketDataValidator;
  @Mock private AccountEntityServiceFactory accountEntityServiceFactory;
  @Mock private PocketRepositoryWrapper pocketRepositoryWrapper;
  @Mock private PocketAccountMappingRepositoryWrapper pocketAccountMappingRepositoryWrapper;
  @Mock private PocketAccountMappingReadPlatformService pocketAccountMappingReadPlatformService;
  @Mock private AccountEntityService accountEntityService;
  @Mock private AppSelfServiceUser user;

  private final FromJsonHelper fromJsonHelper = new FromJsonHelper();

  private PocketWritePlatformServiceImpl service;

  private static final Long USER_ID = 7L;
  private static final Long POCKET_ID = 42L;
  private static final Long ACCOUNT_ID = 11L;
  private static final Long COMMAND_ID = 100L;

  @BeforeEach
  void setUp() {
    service =
        new PocketWritePlatformServiceImpl(
            context,
            pocketDataValidator,
            accountEntityServiceFactory,
            pocketRepositoryWrapper,
            pocketAccountMappingRepositoryWrapper,
            pocketAccountMappingReadPlatformService);
  }

  private void mockAuthenticatedUser() {
    when(user.getId()).thenReturn(USER_ID);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }

  private JsonCommand command(final String json) {
    final JsonElement parsed = this.fromJsonHelper.parse(json);
    return JsonCommand.fromJsonElement(COMMAND_ID, parsed, this.fromJsonHelper);
  }

  private JsonCommand singleLoanLinkCommand() {
    return command("{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"LOAN\"}]}");
  }

  @Test
  void linkAccounts_existingPocket_savesMappingAndReturnsPocketId() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserId(USER_ID)).thenReturn(POCKET_ID);
    when(accountEntityServiceFactory.getAccountEntityService("LOAN"))
        .thenReturn(Optional.of(accountEntityService));
    when(pocketAccountMappingReadPlatformService.validatePocketAndAccountMapping(
            eq(POCKET_ID), eq(ACCOUNT_ID), eq(EntityAccountType.LOAN.getValue())))
        .thenReturn(false);
    when(accountEntityService.retrieveAccountNumberByAccountId(ACCOUNT_ID)).thenReturn("000000011");

    CommandProcessingResult result = service.linkAccounts(singleLoanLinkCommand());

    assertEquals(POCKET_ID, result.getResourceId());
    verify(pocketDataValidator).validateForLinkingAccounts(any());
    verify(accountEntityService).validateSelfUserAccountMapping(ACCOUNT_ID);
    verify(pocketRepositoryWrapper, never()).saveAndFlush(any());
    verify(pocketAccountMappingRepositoryWrapper).save(anyList());
  }

  @Test
  void linkAccounts_noExistingPocket_createsPocketFirst() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserId(USER_ID)).thenReturn(null);
    // Simulate JPA assigning an id on flush so the service can read it back.
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Pocket pocket = invocation.getArgument(0);
              pocket.setId(POCKET_ID);
              return null;
            })
        .when(pocketRepositoryWrapper)
        .saveAndFlush(any(Pocket.class));
    when(accountEntityServiceFactory.getAccountEntityService("LOAN"))
        .thenReturn(Optional.of(accountEntityService));
    when(pocketAccountMappingReadPlatformService.validatePocketAndAccountMapping(
            eq(POCKET_ID), eq(ACCOUNT_ID), eq(EntityAccountType.LOAN.getValue())))
        .thenReturn(false);
    when(accountEntityService.retrieveAccountNumberByAccountId(ACCOUNT_ID)).thenReturn("000000011");

    CommandProcessingResult result = service.linkAccounts(singleLoanLinkCommand());

    assertEquals(POCKET_ID, result.getResourceId());
    verify(pocketRepositoryWrapper).saveAndFlush(any(Pocket.class));
    verify(pocketAccountMappingRepositoryWrapper).save(anyList());
  }

  @Test
  void linkAccounts_duplicateMapping_throwsAndDoesNotSave() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserId(USER_ID)).thenReturn(POCKET_ID);
    when(accountEntityServiceFactory.getAccountEntityService("LOAN"))
        .thenReturn(Optional.of(accountEntityService));
    when(pocketAccountMappingReadPlatformService.validatePocketAndAccountMapping(
            eq(POCKET_ID), eq(ACCOUNT_ID), eq(EntityAccountType.LOAN.getValue())))
        .thenReturn(true);

    JsonCommand command = singleLoanLinkCommand();
    assertThrows(PlatformDataIntegrityException.class, () -> service.linkAccounts(command));
    verify(pocketAccountMappingRepositoryWrapper, never()).save(anyList());
  }

  @Test
  void delinkAccounts_removesRequestedMappingsAndReturnsPocketId() {
    mockAuthenticatedUser();
    when(pocketRepositoryWrapper.findByAppUserIdWithNotFoundDetection(USER_ID))
        .thenReturn(POCKET_ID);
    PocketAccountMapping mapping10 =
        PocketAccountMapping.instance(
            POCKET_ID, 1L, EntityAccountType.LOAN.getValue(), "000000001");
    PocketAccountMapping mapping11 =
        PocketAccountMapping.instance(
            POCKET_ID, 2L, EntityAccountType.SAVINGS.getValue(), "000000002");
    when(pocketAccountMappingRepositoryWrapper.findByIdAndPocketIdWithNotFoundException(
            10L, POCKET_ID))
        .thenReturn(mapping10);
    when(pocketAccountMappingRepositoryWrapper.findByIdAndPocketIdWithNotFoundException(
            11L, POCKET_ID))
        .thenReturn(mapping11);

    CommandProcessingResult result =
        service.delinkAccounts(command("{\"pocketAccountMappingIds\":[10,11]}"));

    assertEquals(POCKET_ID, result.getResourceId());
    verify(pocketDataValidator).validateForDeLinkingAccounts(any());
    verify(pocketAccountMappingRepositoryWrapper).delete(List.of(mapping10, mapping11));
  }
}
