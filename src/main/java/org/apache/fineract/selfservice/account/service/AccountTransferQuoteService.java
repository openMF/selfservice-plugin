/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for calculating transfer fees based on business rules and current exchange
 * rates from the Central Bank of Costa Rica (BCCR).
 *
 * <p>This service integrates with the BCCR exchange rate system to dynamically convert fees defined
 * in USD to CRC using the official sell rate published daily by the Central Bank.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountTransferQuoteService {

  private final SelfServiceTransferFeeRepository feeRepository;
  private final BccrExchangeRateService exchangeRateService;

  /**
   * Calculates the transfer fee for the given request based on configured business rules.
   *
   * @param request the transfer preparation request containing amount, type, currency, and mode
   * @return the calculated fee response with fee amount, total amount, currency, and description
   */
  @Transactional(readOnly = true)
  public AccountTransferQuoteResponse calculateFee(AccountTransferPrepareRequest request) {
    String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : "CRC";
    log.debug(
        "Calculating fee for transfer: type={}, currency={}, mode={}, amount={}",
        request.getTransferType(),
        currency,
        request.getTransferMode(),
        request.getTransferAmount());

    // 1. Fetch configuration from DB
    Optional<SelfServiceTransferFee> configOpt =
        feeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
            request.getTransferType(), currency, request.getTransferMode());

    if (configOpt.isEmpty()) {
      log.info(
          "No fee configuration found for type={}, currency={}, mode={}. Returning zero fee.",
          request.getTransferType(),
          currency,
          request.getTransferMode());
      return new AccountTransferQuoteResponse(
          BigDecimal.ZERO, request.getTransferAmount(), currency, "Sin comisión");
    }

    SelfServiceTransferFee config = configOpt.get();
    BigDecimal fee = BigDecimal.ZERO;
    String feeDescription = config.getDescription();
    BigDecimal exchangeRateUsed = null;

    // 2. Calculate base fee
    if ("PERCENTAGE".equals(config.getFeeType())) {
      fee =
          request
              .getTransferAmount()
              .multiply(config.getFeeValue())
              .setScale(2, RoundingMode.HALF_UP);
      log.debug(
          "Calculated percentage fee: {}% of {} = {}",
          config.getFeeValue().multiply(BigDecimal.valueOf(100)), request.getTransferAmount(), fee);
    } else if ("FIXED".equals(config.getFeeType())) {
      fee = config.getFeeValue();

      // Convert currency if necessary (e.g., USD to CRC) using BCCR exchange rate
      if (config.getFeeCurrency() != null
          && !config.getFeeCurrency().equals(currency)
          && "USD".equals(config.getFeeCurrency())
          && "CRC".equals(currency)) {

        // Try to get BCCR exchange rate
        Optional<BccrExchangeRate> bccrRate = exchangeRateService.getLatestRate();

        if (bccrRate.isPresent()) {
          // Use BCCR sell rate (what the bank charges to sell USD)
          exchangeRateUsed = bccrRate.get().getSellRate();
          fee = fee.multiply(exchangeRateUsed).setScale(2, RoundingMode.HALF_UP);
          log.info(
              "Converted fee from USD to CRC using BCCR rate: {} USD × {} = {} CRC",
              config.getFeeValue(),
              exchangeRateUsed,
              fee);
        } else {
          // Fallback to a standard default rate if BCCR is temporarily unavailable
          exchangeRateUsed = new BigDecimal("515.00");
          fee = fee.multiply(exchangeRateUsed).setScale(2, RoundingMode.HALF_UP);
          log.warn(
              "BCCR exchange rate not available. Using fallback rate: {} USD × {} = {} CRC",
              config.getFeeValue(),
              exchangeRateUsed,
              fee);
        }
      }
    }

    // 3. Apply Threshold Rules (e.g., SINPE Móvil daily limits)
    if (config.getThresholdAmount() != null
        && config.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0) {
      if (request.getTransferAmount().compareTo(config.getThresholdAmount()) <= 0) {
        fee = BigDecimal.ZERO; // Exempt
        log.debug(
            "Transfer amount {} is below threshold {}. Fee exempted.",
            request.getTransferAmount(),
            config.getThresholdAmount());
      } else if (config.getThresholdFeeValue() != null) {
        fee = config.getThresholdFeeValue(); // Apply threshold fee
        log.debug(
            "Transfer amount {} exceeds threshold {}. Applied threshold fee: {}",
            request.getTransferAmount(),
            config.getThresholdAmount(),
            fee);
      }
    }

    // 4. Build enhanced description with exchange rate information
    if (exchangeRateUsed != null) {
      feeDescription =
          String.format(
              "%s (Tasa: %.4f CRC/USD)",
              config.getDescription() != null ? config.getDescription() : "Comisión",
              exchangeRateUsed.doubleValue());
    }

    BigDecimal total = request.getTransferAmount().add(fee);

    log.info(
        "Fee calculation complete: baseFee={}, finalFee={}, total={}, currency={}",
        config.getFeeValue(),
        fee,
        total,
        currency);

    return new AccountTransferQuoteResponse(fee, total, currency, feeDescription);
  }
}