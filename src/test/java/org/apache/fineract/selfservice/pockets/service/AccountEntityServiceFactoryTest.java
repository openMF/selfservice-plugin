/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.pockets.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountEntityServiceFactoryTest {

  @Mock private AccountEntityService loanService;
  @Mock private AccountEntityService savingsService;

  @Test
  void getAccountEntityService_returnsServiceMatchingKey() {
    when(loanService.getKey()).thenReturn(EntityAccountType.LOAN.name());
    when(savingsService.getKey()).thenReturn(EntityAccountType.SAVINGS.name());
    AccountEntityServiceFactory factory =
        new AccountEntityServiceFactory(Set.of(loanService, savingsService));

    assertSame(
        loanService, factory.getAccountEntityService(EntityAccountType.LOAN.name()).orElseThrow());
    assertSame(
        savingsService,
        factory.getAccountEntityService(EntityAccountType.SAVINGS.name()).orElseThrow());
  }

  @Test
  void getAccountEntityService_unknownKey_returnsNull() {
    when(loanService.getKey()).thenReturn(EntityAccountType.LOAN.name());
    AccountEntityServiceFactory factory = new AccountEntityServiceFactory(Set.of(loanService));

    assertTrue(factory.getAccountEntityService("UNKNOWN").isEmpty());
  }
}
