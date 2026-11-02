/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.PaymentLinkConfirmRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkPrepareRequest;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentLinkServiceTest {

  @Mock private SelfServiceTransferFeeRepository transferFeeRepository;
  @Mock private PaymentLinkExternalService paymentLinkExternalService;

  @InjectMocks private PaymentLinkService paymentLinkService;

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-100"})
  void prepare_withMissingOrNonPositiveAmount_isRejected(String amount) {
    PaymentLinkPrepareRequest request = prepareRequest(amount);

    GeneralPlatformDomainRuleException ex =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> paymentLinkService.preparePaymentLink(request));

    assertEquals("error.msg.payment.link.amount.invalid", ex.getGlobalisationMessageCode());
    verifyNoInteractions(transferFeeRepository);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-100"})
  void confirm_withMissingOrNonPositiveAmount_isRejectedBeforeLinkIsCreated(String amount) {
    PaymentLinkConfirmRequest request = new PaymentLinkConfirmRequest();
    request.setClientAccount("1");
    request.setAmount(amount == null ? null : new BigDecimal(amount));
    request.setCurrency("USD");
    request.setTransferType("PAYMENT_LINK");
    request.setTransferMode("INSTANT");

    GeneralPlatformDomainRuleException ex =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> paymentLinkService.confirmPaymentLink(request));

    assertEquals("error.msg.payment.link.amount.invalid", ex.getGlobalisationMessageCode());
    verifyNoInteractions(paymentLinkExternalService);
  }

  @Test
  void prepare_withPositiveAmount_returnsQuote() {
    SelfServiceTransferFee fee = new SelfServiceTransferFee();
    fee.setFeeType("FIXED");
    fee.setFeeValue(new BigDecimal("5"));
    when(transferFeeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
            anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(fee));

    AccountTransferQuoteResponse quote =
        paymentLinkService.preparePaymentLink(prepareRequest("100"));

    assertEquals(0, new BigDecimal("5").compareTo(quote.getFeeAmount()));
    assertEquals(0, new BigDecimal("95").compareTo(quote.getTotalAmount()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"5", "4"})
  void prepare_withFeeNotLowerThanAmount_isRejected(String amount) {
    SelfServiceTransferFee fee = new SelfServiceTransferFee();
    fee.setFeeType("FIXED");
    fee.setFeeValue(new BigDecimal("5"));
    when(transferFeeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
            anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(fee));

    GeneralPlatformDomainRuleException ex =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> paymentLinkService.preparePaymentLink(prepareRequest(amount)));

    assertEquals(
        "error.msg.payment.link.amount.not.greater.than.fee", ex.getGlobalisationMessageCode());
  }

  @Test
  void prepare_withNullRequest_isRejected() {
    GeneralPlatformDomainRuleException ex =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> paymentLinkService.preparePaymentLink(null));

    assertEquals("error.msg.payment.link.invalid.request", ex.getGlobalisationMessageCode());
  }

  @Test
  void confirm_withNullRequest_isRejected() {
    GeneralPlatformDomainRuleException ex =
        assertThrows(
            GeneralPlatformDomainRuleException.class,
            () -> paymentLinkService.confirmPaymentLink(null));

    assertEquals("error.msg.payment.link.invalid.request", ex.getGlobalisationMessageCode());
    verifyNoInteractions(paymentLinkExternalService);
  }

  private PaymentLinkPrepareRequest prepareRequest(String amount) {
    PaymentLinkPrepareRequest request = new PaymentLinkPrepareRequest();
    request.setClientAccount("1");
    request.setAmount(amount == null ? null : new BigDecimal(amount));
    request.setCurrency("USD");
    request.setTransferType("PAYMENT_LINK");
    request.setTransferMode("INSTANT");
    return request;
  }
}
