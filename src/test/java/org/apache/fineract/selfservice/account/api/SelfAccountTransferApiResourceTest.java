/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferData;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.service.AccountTransferQuoteService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferReadService;
import org.apache.fineract.selfservice.account.service.SelfAccountTransferWritePlatformService;
import org.apache.fineract.selfservice.account.service.SelfBeneficiariesTPTReadPlatformService;
import org.apache.fineract.selfservice.account.service.SinpeExternalApiClient;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class SelfAccountTransferApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private SelfAccountTransferWritePlatformService transferWritePlatformService;
  @Mock private AccountTransferQuoteService quoteService;
  @Mock private SinpeExternalApiClient sinpeExternalApiClient;
  @Mock private SelfServiceRegistrationRepository registrationRepository;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private Environment env;
  @Mock private DefaultToApiJsonSerializer<SelfAccountTransferData> toApiJsonSerializer;
  @Mock private PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  @Mock private SelfAccountTransferReadService selfAccountTransferReadService;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  @Mock private ConfigurationDomainService configurationDomainService;
  @Mock private AccountTransfersReadPlatformService accountTransfersReadPlatformService;
  @Mock private SelfServiceOwnershipGuard selfServiceOwnershipGuard;

  private SelfAccountTransferApiResource resource;

  @BeforeEach
  void setUp() {
    // A real validator, because the checks under test live there.
    SelfAccountTransferDataValidator dataValidator =
        new SelfAccountTransferDataValidator(
            context,
            selfAccountTransferReadService,
            tptBeneficiaryReadPlatformService,
            new FromJsonHelper());

    resource =
        new SelfAccountTransferApiResource(
            context,
            transferWritePlatformService,
            quoteService,
            sinpeExternalApiClient,
            registrationRepository,
            applicationEventPublisher,
            env,
            toApiJsonSerializer,
            commandsSourceWritePlatformService,
            selfAccountTransferReadService,
            apiRequestParameterHelper,
            dataValidator,
            tptBeneficiaryReadPlatformService,
            configurationDomainService,
            accountTransfersReadPlatformService,
            selfServiceOwnershipGuard);
  }

  @Test
  void retrieveTransactionDetails_missingBody_throwsInvalidJsonException() {
    assertThrows(InvalidJsonException.class, () -> resource.retrieveTransactionDetails(1L, null));
    assertThrows(InvalidJsonException.class, () -> resource.retrieveTransactionDetails(1L, ""));
    assertThrows(InvalidJsonException.class, () -> resource.retrieveTransactionDetails(1L, "null"));
    verifyNoInteractions(selfAccountTransferReadService);
  }

  @Test
  void retrieveTransactionDetails_bodyNotAnObject_throwsInvalidJsonException() {
    assertThrows(InvalidJsonException.class, () -> resource.retrieveTransactionDetails(1L, "[]"));
    verifyNoInteractions(selfAccountTransferReadService);
  }

  @Test
  void retrieveTransactionDetails_missingTxnId_throwsValidationErrorOnTxnId() {
    assertTxnIdError(
        () -> resource.retrieveTransactionDetails(1L, "{\"transferType\":\"SAME_BANK\"}"));
  }

  @Test
  void retrieveTransactionDetails_blankTxnId_throwsValidationErrorOnTxnId() {
    assertTxnIdError(() -> resource.retrieveTransactionDetails(1L, "{\"txnId\":\"   \"}"));
  }

  @Test
  void retrieveTransactionDetails_objectOrArrayTxnId_throwsValidationErrorOnTxnId() {
    assertTxnIdError(() -> resource.retrieveTransactionDetails(1L, "{\"txnId\":{}}"));
    assertTxnIdError(() -> resource.retrieveTransactionDetails(1L, "{\"txnId\":[]}"));
  }

  @Test
  void retrieveTransactionDetails_numericTxnId_isPassedOnAsText() {
    // Transaction ids can be numeric (audit id, Fineract transfer id, savings transaction id).
    resource.retrieveTransactionDetails(1L, "{\"txnId\":12345}");
    verify(selfAccountTransferReadService).retrieveTransactionDetails(1L, "12345", "SAME_BANK");
  }

  @Test
  void retrieveTransactionDetails_noTransferType_defaultsToSameBank() {
    resource.retrieveTransactionDetails(1L, "{\"txnId\":\"abc\"}");
    verify(selfAccountTransferReadService).retrieveTransactionDetails(1L, "abc", "SAME_BANK");
  }

  @Test
  void retrieveTransactionDetails_blankTransferType_defaultsToSameBank() {
    resource.retrieveTransactionDetails(1L, "{\"txnId\":\"abc\",\"transferType\":\"   \"}");
    verify(selfAccountTransferReadService).retrieveTransactionDetails(1L, "abc", "SAME_BANK");
  }

  @Test
  void retrieveTransactionDetails_withTransferType_passesItThrough() {
    resource.retrieveTransactionDetails(1L, "{\"txnId\":\"abc\",\"transferType\":\"SINPE\"}");
    verify(selfAccountTransferReadService).retrieveTransactionDetails(1L, "abc", "SINPE");
  }

  @Test
  void retrieveTransactionDetailsGet_blankTxnId_throwsValidationErrorOnTxnId() {
    assertTxnIdError(() -> resource.retrieveTransactionDetailsGet(1L, " ", "SAME_BANK"));
  }

  @Test
  void retrieveTransactionDetailsGet_validTxnId_callsReadService() {
    resource.retrieveTransactionDetailsGet(1L, "abc", "SAME_BANK");
    verify(selfAccountTransferReadService).retrieveTransactionDetails(1L, "abc", "SAME_BANK");
  }

  @Test
  void retrieveTransactionDetailsGet_blankTransferType_defaultsToSameBank() {
    // ?transferType= arrives as "", since @DefaultValue only covers a missing parameter.
    resource.retrieveTransactionDetailsGet(1L, "abc", "");
    verify(selfAccountTransferReadService).retrieveTransactionDetails(1L, "abc", "SAME_BANK");
  }

  private void assertTxnIdError(Executable call) {
    PlatformApiDataValidationException error =
        assertThrows(PlatformApiDataValidationException.class, call);
    assertEquals(1, error.getErrors().size());
    ApiParameterError txnIdError = error.getErrors().get(0);
    assertEquals("txnId", txnIdError.getParameterName());
    assertEquals(
        "validation.msg.accounttransfer.txnId.cannot.be.blank",
        txnIdError.getUserMessageGlobalisationCode());
    verifyNoInteractions(selfAccountTransferReadService);
  }
}
