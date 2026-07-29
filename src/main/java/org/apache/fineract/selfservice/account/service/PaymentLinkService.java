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
        log.info("Preparing payment link quote - amount: {}, currency: {}, transferType: {}, transferMode: {}, clientAccount: {}",
                request.getAmount(), request.getCurrency(), request.getTransferType(),
                request.getTransferMode(), request.getClientAccount());

        String transferType = request.getTransferType() != null ? request.getTransferType() : "PAYMENT_LINK";
        String transferMode = request.getTransferMode() != null ? request.getTransferMode() : "INSTANT";
        String currencyCode = request.getCurrency() != null ? request.getCurrency() : "USD";

        if (request.getTransferType() == null || request.getTransferType().isBlank()) {
            log.warn("Payment link prepare rejected: transferType is required. clientAccount={}", request.getClientAccount());
            throw new GeneralPlatformDomainRuleException(
                    "error.msg.payment.link.transfer.type.required",
                    "Transfer Type is required to calculate the fee for confirmation"
            );
        }

        log.info("Looking up active fee config for transferType={}, transferMode={}, currency={}",
                transferType, transferMode, currencyCode);

        Optional<SelfServiceTransferFee> feeOpt = transferFeeRepository
                .findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(transferType, currencyCode, transferMode);

        BigDecimal feeAmount = BigDecimal.ZERO;
        String feeDescription = "No fee applied for " + transferType;

        if (feeOpt.isPresent()) {
            SelfServiceTransferFee fee = feeOpt.get();
            log.info("Fee config found - id={}, feeType={}, feeValue={}, thresholdAmount={}, thresholdFeeValue={}",
                    fee.getId(), fee.getFeeType(), fee.getFeeValue(),
                    fee.getThresholdAmount(), fee.getThresholdFeeValue());

            if ("FIXED".equalsIgnoreCase(fee.getFeeType())) {
                feeAmount = fee.getFeeValue();
                log.info("Applied FIXED fee: {}", feeAmount);
            } else if ("PERCENTAGE".equalsIgnoreCase(fee.getFeeType())) {
                feeAmount = request.getAmount()
                        .multiply(fee.getFeeValue())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                log.info("Applied PERCENTAGE fee: {}% of {} = {}", fee.getFeeValue(), request.getAmount(), feeAmount);
            } else {
                log.warn("Unknown feeType '{}' for fee id={}. Treating as zero fee.", fee.getFeeType(), fee.getId());
            }

            // Apply threshold fee if configured and amount exceeds threshold
            if (fee.getThresholdAmount() != null && request.getAmount().compareTo(fee.getThresholdAmount()) > 0) {
                if (fee.getThresholdFeeValue() != null) {
                    log.info("Amount {} exceeds threshold {}. Overriding fee with thresholdFeeValue={}",
                            request.getAmount(), fee.getThresholdAmount(), fee.getThresholdFeeValue());
                    feeAmount = fee.getThresholdFeeValue();
                } else {
                    log.info("Amount exceeds threshold but no thresholdFeeValue configured. Keeping calculated fee.");
                }
            }

            feeDescription = fee.getDescription() != null ? fee.getDescription() : transferType + " processing fee";
        } else {
            log.info("No active fee configuration found for transferType={}, transferMode={}, currency={}. Applying zero fee.",
                    transferType, transferMode, currencyCode);
        }

        BigDecimal totalAmount = request.getAmount().add(feeAmount);

        log.info("Payment link quote prepared successfully - principal: {}, fee: {}, total: {}, currency: {}, description: {}",
                request.getAmount(), feeAmount, totalAmount, request.getCurrency(), feeDescription);

        return new AccountTransferQuoteResponse(
                feeAmount,
                totalAmount,
                request.getCurrency(),
                feeDescription
        );
    }

    @Transactional
    public PaymentLinkResponse confirmPaymentLink(PaymentLinkConfirmRequest request) {
        log.info("Confirming payment link - amount: {}, currency: {}, transferType: {}, transferMode: {}, clientAccount: {}, payerEmail: {}",
                request.getAmount(), request.getCurrency(), request.getTransferType(),
                request.getTransferMode(), request.getClientAccount(), request.getPayerEmail());

        if (request.getTransferType() == null || request.getTransferType().isBlank()) {
            log.warn("Payment link confirm rejected: transferType is required. clientAccount={}", request.getClientAccount());
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

        log.debug("Recalculating fee quote before external payment link creation");
        AccountTransferQuoteResponse quote = preparePaymentLink(prepareRequest);
        log.debug("Recalculated quote - fee: {}, totalAmount: {}", quote.getFeeAmount(), quote.getTotalAmount());

        // 2. Build external request with the total amount (principal + fee)
        // Note: currently the external request still carries the original principal amount.
        // If the external service is expected to receive the total (principal + fee), adjust here.
        PaymentLinkRequest externalRequest = new PaymentLinkRequest();
        externalRequest.setPayerName(request.getPayerName());
        externalRequest.setPayerEmail(request.getPayerEmail());
        externalRequest.setPayerPhone(request.getPayerPhone());
        externalRequest.setClientAccount(request.getClientAccount());
        externalRequest.setAmount(request.getAmount());
        externalRequest.setCurrency(request.getCurrency());
        externalRequest.setDescription(request.getDescription());

        log.info("Delegating payment link creation to external service for clientAccount={}, amount={}",
                request.getClientAccount(), request.getAmount());

        // 3. Execute external payment link creation
        PaymentLinkResponse response = paymentLinkExternalService.createPaymentLink(externalRequest);

        log.info("Payment link confirmed successfully - clientAccount={}, responseId/status available in external response",
                request.getClientAccount());

        return response;
    }

    @Transactional
    public PaymentLinkResponse createPaymentLink(PaymentLinkRequest request) {
        log.info("Creating payment link (direct) - amount: {}, currency: {}, clientAccount: {}, payerEmail: {}",
                request.getAmount(), request.getCurrency(), request.getClientAccount(), request.getPayerEmail());

        PaymentLinkResponse response = paymentLinkExternalService.createPaymentLink(request);

        log.info("Payment link created successfully via external service for clientAccount={}",
                request.getClientAccount());

        return response;
    }
}