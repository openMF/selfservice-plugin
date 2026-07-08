package org.apache.fineract.selfservice.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountTransferQuoteService {

  private final SelfServiceTransferFeeRepository feeRepository;

  public AccountTransferQuoteResponse calculateFee(AccountTransferPrepareRequest request) {
    String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : "CRC";

    // 1. Fetch configuration from DB
    Optional<SelfServiceTransferFee> configOpt =
        feeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
            request.getTransferType(), currency, request.getTransferMode());

    if (configOpt.isEmpty()) {
      return new AccountTransferQuoteResponse(
          BigDecimal.ZERO, request.getTransferAmount(), currency, "Sin comisión");
    }

    SelfServiceTransferFee config = configOpt.get();
    BigDecimal fee = BigDecimal.ZERO;

    // 2. Calculate base fee
    if ("PERCENTAGE".equals(config.getFeeType())) {
      fee =
          request
              .getTransferAmount()
              .multiply(config.getFeeValue())
              .setScale(2, RoundingMode.HALF_UP);
    } else if ("FIXED".equals(config.getFeeType())) {
      fee = config.getFeeValue();
      // Convert currency if necessary (e.g., USD to CRC)
      if (config.getFeeCurrency() != null
          && !config.getFeeCurrency().equals(currency)
          && config.getExchangeRate() != null) {
        fee = fee.multiply(config.getExchangeRate()).setScale(2, RoundingMode.HALF_UP);
      }
    }

    // 3. Apply Threshold Rules (e.g., SINPE Móvil daily limits)
    if (config.getThresholdAmount() != null
        && config.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0) {
      if (request.getTransferAmount().compareTo(config.getThresholdAmount()) <= 0) {
        fee = BigDecimal.ZERO; // Exempt
      } else if (config.getThresholdFeeValue() != null) {
        fee = config.getThresholdFeeValue(); // Apply threshold fee
      }
    }

    BigDecimal total = request.getTransferAmount().add(fee);
    return new AccountTransferQuoteResponse(fee, total, currency, config.getDescription());
  }
}
