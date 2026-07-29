/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.PaymentLinkConfirmRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkPrepareRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLinkService {

    private final SelfServiceTransferFeeRepository transferFeeRepository;
    private final PaymentLinkExternalService paymentLinkExternalService;

    @Transactional(readOnly = true)
    public AccountTransferQuoteResponse preparePaymentLink(PaymentLinkPrepareRequest request) {
        String transferType = request.getTransferType() != null ? request.getTransferType() : "PAYMENT_LINK";
        String transferMode = request.getTransferMode() != null ? request.getTransferMode() : "INSTANT";
        String currency = request.getCurrency() != null ? request.getCurrency() : "USD";
        if (request.getTransferType() == null || request.getTransferType().isBlank()) {
            throw new GeneralPlatformDomainRuleException(
                "error.msg.payment.link.transfer.type.required",
                "Transfer Type is required to calculate the fee for confirmation"
            );
        }        
        
        Optional<SelfServiceTransferFee> feeOpt = transferFeeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(transferType, transferMode, currency);

        BigDecimal feeAmount = BigDecimal.ZERO;
        String feeDescription = "No fee applied for " + transferType;

        if (feeOpt.isPresent()) {
            SelfServiceTransferFee fee = feeOpt.get();
            
            if ("FIXED".equalsIgnoreCase(fee.getFeeType())) {
                feeAmount = fee.getFeeValue();
            } else if ("PERCENTAGE".equalsIgnoreCase(fee.getFeeType())) {
                feeAmount = request.getAmount()
                        .multiply(fee.getFeeValue())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }
            
            // Apply threshold fee if configured and amount exceeds threshold
            if (fee.getThresholdAmount() != null && request.getAmount().compareTo(fee.getThresholdAmount()) > 0) {
                if (fee.getThresholdFeeValue() != null) {
                    feeAmount = fee.getThresholdFeeValue();
                }
            }
            
            feeDescription = fee.getDescription() != null ? fee.getDescription() : transferType + " processing fee";
        }

        BigDecimal totalAmount = request.getAmount().add(feeAmount);

        return new AccountTransferQuoteResponse(
                feeAmount,
                totalAmount,
                request.getCurrency(),
                feeDescription
        );
    }

    @Transactional
    public PaymentLinkResponse confirmPaymentLink(PaymentLinkConfirmRequest request) {
        if (request.getTransferType() == null || request.getTransferType().isBlank()) {
            throw new GeneralPlatformDomainRuleException(
                "error.msg.payment.link.transfer.type.required",
                "Transfer Type is required to calculate the fee for confirmation"
            );
        }

        // 1. Recalculate fee to ensure consistency (OTP is not required per specifications)
        PaymentLinkPrepareRequest prepareRequest = new PaymentLinkPrepareRequest();
        prepareRequest.setClientAccount(request.getClientAccount());
        prepareRequest.setAmount(request.getAmount());
        prepareRequest.setCurrency(request.getCurrency());
        prepareRequest.setTransferType(request.getTransferType());
        prepareRequest.setTransferMode(request.getTransferMode());
        prepareRequest.setDescription(request.getDescription());
        
        AccountTransferQuoteResponse quote = preparePaymentLink(prepareRequest);
        
        // 2. Build external request with the total amount (principal + fee)
        PaymentLinkRequest externalRequest = new PaymentLinkRequest();
        externalRequest.setPayerName(request.getPayerName());
        externalRequest.setPayerEmail(request.getPayerEmail());
        externalRequest.setPayerPhone(request.getPayerPhone());
        externalRequest.setClientAccount(request.getClientAccount());
        externalRequest.setAmount(request.getAmount());
        externalRequest.setCurrency(request.getCurrency());
        externalRequest.setDescription(request.getDescription());
        
        // 3. Execute external payment link creation
        return paymentLinkExternalService.createPaymentLink(externalRequest);
    }

    @Transactional
    public PaymentLinkResponse createPaymentLink(PaymentLinkRequest request) {
        return paymentLinkExternalService.createPaymentLink(request);
    }
}