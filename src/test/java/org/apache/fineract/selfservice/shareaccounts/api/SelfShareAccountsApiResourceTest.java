/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.shareaccounts.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.serialization.GoogleGsonSerializerHelper;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.portfolio.accounts.api.AccountsApiResource;
import org.apache.fineract.portfolio.accounts.constants.ShareAccountApiConstants;
import org.apache.fineract.portfolio.accounts.data.AccountData;
import org.apache.fineract.portfolio.accounts.data.request.AccountRequest;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.products.service.ShareProductReadPlatformService;
import org.apache.fineract.portfolio.shareaccounts.service.ShareAccountReadPlatformService;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.shareaccounts.data.SelfShareAccountsDataValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfShareAccountsApiResourceTest {

  private static final Long CLIENT_ID = 14L;

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private AccountsApiResource accountsApiResource;
  @Mock private ShareAccountReadPlatformService readPlatformService;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private SelfServiceOwnershipGuard selfServiceOwnershipGuard;
  @Mock private ShareProductReadPlatformService shareProductReadPlatformService;
  @Mock private ChargeReadPlatformService chargeReadPlatformService;

  private SelfShareAccountsApiResource resource;

  @BeforeEach
  void setUp() {
    // Real serializer and validator, so the tests cover the actual request-to-JSON round trip.
    final DefaultToApiJsonSerializer<AccountData> toApiJsonSerializer =
        new DefaultToApiJsonSerializer<>(
            new ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson(),
            new CommandProcessingResultJsonSerializer(),
            new GoogleGsonSerializerHelper());
    resource =
        new SelfShareAccountsApiResource(
            context,
            accountsApiResource,
            readPlatformService,
            apiRequestParameterHelper,
            toApiJsonSerializer,
            selfServiceOwnershipGuard,
            new SelfShareAccountsDataValidator(new FromJsonHelper()),
            shareProductReadPlatformService,
            chargeReadPlatformService);
  }

  private static AccountRequest validRequest() {
    final AccountRequest request = new AccountRequest();
    request.setClientId(CLIENT_ID);
    request.setProductId(1L);
    request.setSavingsAccountId(5L);
    request.setRequestedShares(10L);
    request.setSubmittedDate("02 November 2026");
    request.setApplicationDate("02 November 2026");
    request.setDateFormat("dd MMMM yyyy");
    request.setLocale("en");
    return request;
  }

  @Test
  void createAccount_validRequest_isPassedToFineract() {
    final AccountRequest request = validRequest();
    final CommandProcessingResult expected = mock(CommandProcessingResult.class);
    when(accountsApiResource.createAccount(ShareAccountApiConstants.shareEntityType, request))
        .thenReturn(expected);

    final CommandProcessingResult result = resource.createAccount(request);

    assertSame(expected, result);
    verify(selfServiceOwnershipGuard).validateClientOwnership(CLIENT_ID);
  }

  @Test
  void createAccount_nullBody_throwsInvalidJson() {
    assertThrows(InvalidJsonException.class, () -> resource.createAccount(null));

    verify(selfServiceOwnershipGuard, never()).validateClientOwnership(anyLong());
    verify(accountsApiResource, never()).createAccount(anyString(), any());
  }

  @Test
  void createAccount_missingClientId_failsValidation() {
    final AccountRequest request = validRequest();
    request.setClientId(null);

    assertThrows(PlatformApiDataValidationException.class, () -> resource.createAccount(request));

    verify(accountsApiResource, never()).createAccount(anyString(), any());
  }

  @Test
  void createAccount_otherClientsId_isDenied() {
    final AccountRequest request = validRequest();
    doThrow(new NoAuthorizationException("not your client"))
        .when(selfServiceOwnershipGuard)
        .validateClientOwnership(CLIENT_ID);

    assertThrows(NoAuthorizationException.class, () -> resource.createAccount(request));

    verify(accountsApiResource, never()).createAccount(anyString(), any());
  }
}
