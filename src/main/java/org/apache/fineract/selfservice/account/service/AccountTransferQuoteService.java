/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAuditRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for calculating transfer fees based on business rules and current exchange
 * rates from the Central Bank of Costa Rica (BCCR).
 *
 * <p>Fee calculation mirrors {@code executeFeeTransaction} so that the amount quoted to the client
 * matches the amount later collected on confirm. For SINPE_MOVIL with a DAILY threshold, the
 * projected total (already transferred today + current amount) is evaluated against the threshold.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountTransferQuoteService {

  private final SelfServiceTransferFeeRepository feeRepository;
  private final BccrExchangeRateService exchangeRateService;
  private final SelfServiceTransferAuditRepository transferAuditRepository;
  private final PlatformSelfServiceSecurityContext context;

  /**
   * Calculates the transfer fee for the given request based on configured business rules.
   *
   * @param request the transfer preparation request containing amount, type, currency, and mode
   * @return the calculated fee response with fee amount, total amount, currency, and description
   */
  @Transactional(readOnly = true)
  public AccountTransferQuoteResponse calculateFee(AccountTransferPrepareRequest request) {
    String currency =
        StringUtils.isNotBlank(request.getCurrencyCode()) ? request.getCurrencyCode() : "CRC";
    String transferMode =
        StringUtils.isNotBlank(request.getTransferMode()) ? request.getTransferMode() : "INSTANT";

    log.debug(
        "Calculating fee for transfer: type={}, currency={}, mode={}, amount={}",
        request.getTransferType(),
        currency,
        transferMode,
        request.getTransferAmount());

    // 1. Resolve fee configuration (same rules used by executeFeeTransaction)
    Optional<SelfServiceTransferFee> configOpt =
        feeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
            request.getTransferType(), currency, transferMode);

    if (configOpt.isEmpty()) {
      log.info(
          "No fee configuration found for type={}, currency={}, mode={}. Returning zero fee.",
          request.getTransferType(),
          currency,
          transferMode);
      return new AccountTransferQuoteResponse(
          BigDecimal.ZERO, request.getTransferAmount(), currency, "Sin comisión");
    }

    SelfServiceTransferFee config = configOpt.get();
    BigDecimal fee = BigDecimal.ZERO;
    String feeDescription =
        StringUtils.defaultIfBlank(config.getDescription(), "Comisión de transferencia");

    // 2. Calculate base fee (mirrors confirm logic)
    if ("PERCENTAGE".equalsIgnoreCase(config.getFeeType())) {
      fee =
          request
              .getTransferAmount()
              .multiply(config.getFeeValue())
              .setScale(2, RoundingMode.HALF_UP);
      log.debug(
          "Calculated percentage fee: {} of {} = {}",
          config.getFeeValue(),
          request.getTransferAmount(),
          fee);
    } else { // FIXED
      fee = config.getFeeValue();

      // Currency conversion when the fee is stored in a different currency
      // (classic case: fee expressed in CRC → convert to USD for a USD transfer)
      if (config.getFeeCurrency() != null
          && !config.getFeeCurrency().equalsIgnoreCase(currency)
          && "CRC".equalsIgnoreCase(config.getFeeCurrency())
          && "USD".equalsIgnoreCase(currency)) {

        Optional<BccrExchangeRate> bccrRate = exchangeRateService.getLatestRate();
        BigDecimal rate =
            bccrRate
                .map(BccrExchangeRate::getSellRate)
                .orElseThrow(() -> new IllegalArgumentException("Exchange Rate not found"));

        // fee is in CRC → convert to USD (divide by BCCR sell rate)
        fee = fee.divide(rate, 2, RoundingMode.HALF_UP);
        feeDescription =
            String.format("%s (Tasa BCCR venta: %s CRC/USD)", feeDescription, rate.toPlainString());
        log.info(
            "Converted CRC fee {} → {} USD using BCCR sell rate {}",
            config.getFeeValue(),
            fee,
            rate);
      }
    }

    // 3. Apply daily threshold (SINPE_MOVIL) — same projected-total logic as confirm
    if ("DAILY".equalsIgnoreCase(config.getThresholdPeriod())
        && config.getThresholdAmount() != null
        && config.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0) {

      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      Client client = user.getAppUserClientMappings().iterator().next().getClient();
      LocalDate today = DateUtils.getLocalDateOfTenant();

      BigDecimal alreadyTransferredToday =
          transferAuditRepository.getDailySinpeMovilTotal(client.getId(), today);
      if (alreadyTransferredToday == null) {
        alreadyTransferredToday = BigDecimal.ZERO;
      }

      BigDecimal projected = alreadyTransferredToday.add(request.getTransferAmount());

      if (projected.compareTo(config.getThresholdAmount()) <= 0) {
        fee = BigDecimal.ZERO;
        feeDescription = "Exento: Dentro del umbral diario SINPE Móvil";
        log.info(
            "SINPE_MOVIL threshold: projected {} ≤ {}. Fee waived.",
            projected,
            config.getThresholdAmount());
      } else {
        if (config.getThresholdFeeValue() != null) {
          fee = config.getThresholdFeeValue();
        }
        log.info("SINPE_MOVIL threshold exceeded (projected {}). Applying fee {}.", projected, fee);
      }
    } else if (config.getThresholdAmount() != null
        && config.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0) {
      // Non-daily threshold: compare current amount only
      if (request.getTransferAmount().compareTo(config.getThresholdAmount()) <= 0) {
        fee = BigDecimal.ZERO;
        log.debug(
            "Transfer amount {} is at or below threshold {}. Fee exempted.",
            request.getTransferAmount(),
            config.getThresholdAmount());
      } else if (config.getThresholdFeeValue() != null) {
        fee = config.getThresholdFeeValue();
        log.debug(
            "Transfer amount {} exceeds threshold {}. Applied threshold fee: {}",
            request.getTransferAmount(),
            config.getThresholdAmount(),
            fee);
      }
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
