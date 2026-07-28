/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.selfservice.account.data.FeeCollectionRequest;
import org.apache.fineract.selfservice.account.data.FeeCollectionResult;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountForFeesRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAuditRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fee-collection logic extracted from {@code SelfAccountTransferWritePlatformServiceImpl} and
 * executed inside a {@code REQUIRES_NEW} transaction.
 *
 * <h3>Why REQUIRES_NEW?</h3>
 *
 * The calling service ({@code confirmTransfer}) already loaded the source {@code SavingsAccount}
 * into its persistence context during balance validation. Fineract's core {@code
 * AccountTransfersWritePlatformService.create()} then tries to modify and flush the <em>same</em>
 * entity, triggering an {@code OptimisticLockException} (EclipseLink-5006). A separate transaction
 * gets its own persistence context and loads the entity at the current DB version, eliminating the
 * conflict.
 *
 * <h3>Multi-tenancy</h3>
 *
 * Fineract stores the tenant identifier in a {@code ThreadLocal}. Because {@code REQUIRES_NEW}
 * suspends (not migrates) the transaction on the same thread, the tenant context is fully
 * preserved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceFeeCollectionServiceImpl implements SelfServiceFeeCollectionService {

  private final SelfServiceAccountForFeesRepository externalServicePropertiesRepository;
  private final SelfServiceTransferFeeRepository feeRepository;
  private final BccrExchangeRateService bccrExchangeRateService;
  private final SelfServiceTransferAuditRepository transferAuditRepository;
  private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
  private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
  private final ExternalIdFactory externalIdFactory;
  private final SavingsAccountAssembler savingsAccountAssembler;
  private final LoanAssembler loanAssembler;
  private final FromJsonHelper fromApiJsonHelper;

  private final Gson gson = new Gson();

  // ===================================================================
  //  PUBLIC ENTRY POINT — runs in its own transaction
  // ===================================================================
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public FeeCollectionResult collectFee(final FeeCollectionRequest req) {
    log.info(
        "FEE_COLLECT [REQUIRES_NEW]: Starting for type={}, currency={}, amount={}",
        req.getTransferType(),
        req.getCurrencyCode(),
        req.getTransferAmount());

    try {
      // ---------------------------------------------------------------
      // 1. Multi-tenant feature flag
      // ---------------------------------------------------------------
      Map<String, String> feeCfg =
          externalServicePropertiesRepository.getProperties("SELF_SERVICE_FEE_CONFIG");

      boolean enabled = Boolean.parseBoolean(feeCfg.getOrDefault("transfer_fee_enabled", "false"));
      if (!enabled) {
        log.info("FEE_COLLECT: Disabled in tenant configuration. Skipping.");
        return skipped("Fee collection disabled in tenant configuration.");
      }

      // ---------------------------------------------------------------
      // 2. Institution GL destination accounts
      // ---------------------------------------------------------------
      Map<String, String> instCfg =
          externalServicePropertiesRepository.getProperties("INSTITUTION_ACCOUNT_FOR_FEES");

      // ---------------------------------------------------------------
      // 3. Resolve fee row
      // ---------------------------------------------------------------
      String currency =
          StringUtils.isNotBlank(req.getCurrencyCode()) ? req.getCurrencyCode() : "CRC";
      String transferMode =
          StringUtils.isNotBlank(req.getTransferMode()) ? req.getTransferMode() : "INMEDIATA";

      Optional<SelfServiceTransferFee> feeOpt =
          feeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
              req.getTransferType(), currency, transferMode);

      if (feeOpt.isEmpty()) {
        log.info(
            "FEE_COLLECT: No active fee for type={}, currency={}, mode={}. Skipping.",
            req.getTransferType(),
            currency,
            transferMode);
        return skipped("No active fee configuration.");
      }

      SelfServiceTransferFee feeConfig = feeOpt.get();

      // ---------------------------------------------------------------
      // 4. Calculate concrete fee amount
      // ---------------------------------------------------------------
      BigDecimal feeAmount = BigDecimal.ZERO;
      String feeDescription =
          StringUtils.defaultIfBlank(feeConfig.getDescription(), "Comisión de transferencia");

      if ("PERCENTAGE".equalsIgnoreCase(feeConfig.getFeeType())) {
        feeAmount =
            req.getTransferAmount()
                .multiply(feeConfig.getFeeValue())
                .setScale(2, RoundingMode.HALF_UP);
      } else {
        // FIXED
        feeAmount = feeConfig.getFeeValue();

        // CRC → USD conversion via BCCR sell rate
        if (feeConfig.getFeeCurrency() != null
            && !feeConfig.getFeeCurrency().equalsIgnoreCase(currency)
            && "CRC".equalsIgnoreCase(feeConfig.getFeeCurrency())
            && "USD".equalsIgnoreCase(currency)) {

          Optional<BccrExchangeRate> bccrRate = bccrExchangeRateService.getLatestRate();
          BigDecimal rate =
              bccrRate
                  .map(BccrExchangeRate::getSellRate)
                  .orElseThrow(() -> new IllegalStateException("BCCR exchange rate not available"));

          feeAmount = feeAmount.divide(rate, 2, RoundingMode.HALF_UP);
          feeDescription =
              String.format(
                  "%s (Tasa BCCR venta: %s CRC/USD)", feeDescription, rate.toPlainString());
          log.info(
              "FEE_COLLECT: Converted CRC fee {} → {} USD using BCCR sell rate {}",
              feeConfig.getFeeValue(),
              feeAmount,
              rate);
        }
      }

      // ---------------------------------------------------------------
      // 5. Daily threshold (SINPE_MOVIL)
      // ---------------------------------------------------------------
      if ("DAILY".equalsIgnoreCase(feeConfig.getThresholdPeriod())
          && feeConfig.getThresholdAmount() != null
          && feeConfig.getThresholdAmount().compareTo(BigDecimal.ZERO) > 0) {

        LocalDate today = DateUtils.getLocalDateOfTenant();
        BigDecimal alreadyToday =
            transferAuditRepository.getDailySinpeMovilTotal(req.getClientId(), today);
        if (alreadyToday == null) {
          alreadyToday = BigDecimal.ZERO;
        }

        BigDecimal projected = alreadyToday.add(req.getTransferAmount());

        if (projected.compareTo(feeConfig.getThresholdAmount()) <= 0) {
          feeAmount = BigDecimal.ZERO;
          feeDescription = "Exento: Dentro del umbral diario SINPE Móvil";
          log.info(
              "FEE_COLLECT: SINPE threshold OK (projected {} ≤ {}). Fee waived.",
              projected,
              feeConfig.getThresholdAmount());
        } else if (feeConfig.getThresholdFeeValue() != null) {
          feeAmount = feeConfig.getThresholdFeeValue();
          log.info(
              "FEE_COLLECT: SINPE threshold exceeded (projected {}). Applying threshold fee {}.",
              projected,
              feeAmount);
        }
      }

      if (feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
        log.info("FEE_COLLECT: Final fee is zero. Nothing to charge.");
        persistAudit(req, BigDecimal.ZERO, "COMPLETED");
        return FeeCollectionResult.builder()
            .successful(true)
            .status(FeeCollectionResult.Status.SKIPPED)
            .feeAmount(BigDecimal.ZERO)
            .currency(currency)
            .message(feeDescription)
            .build();
      }

      // ---------------------------------------------------------------
      // 6. Build & execute the internal Fineract transfer
      // ---------------------------------------------------------------
      Long toOfficeId = Long.valueOf(instCfg.get("to_office_id"));
      Long toClientId = Long.valueOf(instCfg.get("to_client_id"));
      Integer toAccountType = Integer.valueOf(instCfg.get("to_account_type"));
      String toAccountIdStr =
          "USD".equalsIgnoreCase(currency)
              ? instCfg.get("to_account_id_usd")
              : instCfg.get("to_account_id_crc");
      Long toAccountId = Long.valueOf(toAccountIdStr);

      log.info(
          "FEE_COLLECT: Destination GL → office={}, client={}, type={}, account={}",
          toOfficeId,
          toClientId,
          toAccountType,
          toAccountId);

      // Resolve source account INSIDE this fresh persistence context
      Long fromAccountId = resolveAccountId(req.getFromAccount(), req.getFromAccountType());

      Map<String, Object> cmd = new HashMap<>();
      cmd.put("fromOfficeId", req.getFromOfficeId());
      cmd.put("fromClientId", req.getClientId());
      cmd.put("fromAccountType", 2);
      cmd.put("fromAccountId", fromAccountId);
      cmd.put("toOfficeId", toOfficeId);
      cmd.put("toClientId", toClientId);
      cmd.put("toAccountType", toAccountType);
      cmd.put("toAccountId", toAccountId);
      cmd.put("transferAmount", feeAmount);
      cmd.put("transferDate", req.getTransferDateForFineract());
      cmd.put("transferDescription", feeDescription);
      cmd.put("locale", req.getLocale());
      cmd.put("dateFormat", req.getDateFormat());

      String json = gson.toJson(cmd);
      JsonCommand command = createJsonCommand(json);

      log.info("FEE_COLLECT: Executing commission transfer of {} {}", feeAmount, currency);
      CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

      if (result != null && result.getResourceId() != null) {
        log.info(
            "FEE_COLLECT: Commission collected. txnId={}, amount={} {}",
            result.getResourceId(),
            feeAmount,
            currency);
        persistAudit(req, feeAmount, "COMPLETED");
        return FeeCollectionResult.builder()
            .successful(true)
            .status(FeeCollectionResult.Status.COMPLETED)
            .transactionId(result.getResourceId())
            .feeAmount(feeAmount)
            .currency(currency)
            .message("Commission collected successfully.")
            .build();
      }

      log.warn("FEE_COLLECT: Command returned no resourceId.");
      persistAudit(req, feeAmount, "FAILED");
      return FeeCollectionResult.builder()
          .successful(false)
          .status(FeeCollectionResult.Status.FAILED)
          .feeAmount(feeAmount)
          .currency(currency)
          .message("Transfer command returned no resource id.")
          .build();

    } catch (Exception e) {
      // Let the REQUIRES_NEW transaction roll back cleanly.
      // The caller catches this and records a FAILED audit in its own TX.
      log.error("FEE_COLLECT: Commission collection failed. Rolling back fee TX.", e);
      throw new RuntimeException("Fee collection failed: " + e.getMessage(), e);
    }
  }

  // ===================================================================
  //  PRIVATE HELPERS  (self-contained — no shared state with caller)
  // ===================================================================

  private FeeCollectionResult skipped(String reason) {
    return FeeCollectionResult.builder()
        .successful(true)
        .status(FeeCollectionResult.Status.SKIPPED)
        .feeAmount(BigDecimal.ZERO)
        .message(reason)
        .build();
  }

  private void persistAudit(FeeCollectionRequest req, BigDecimal fee, String status) {
    try {
      SelfServiceTransferAudit audit =
          SelfServiceTransferAudit.builder()
              .clientId(req.getClientId())
              .transferType(req.getTransferType())
              .currencyCode(req.getCurrencyCode())
              .transferAmount(req.getTransferAmount())
              .feeAmount(fee)
              .processingDate(OffsetDateTime.now())
              .status(status)
              .build();
      transferAuditRepository.saveAndFlush(audit);
    } catch (Exception e) {
      log.error("FEE_COLLECT: Failed to persist audit (non-fatal)", e);
    }
  }

  /**
   * Resolves an account identifier (numeric id, IBAN, or external-id) to the internal Fineract
   * account id. Runs inside the REQUIRES_NEW persistence context so the returned entity is
   * <b>not</b> the stale one from the caller.
   */
  private Long resolveAccountId(String accountIdentifier, Integer accountType) {
    if (StringUtils.isBlank(accountIdentifier)) {
      throw new IllegalArgumentException("Account identifier cannot be null or blank.");
    }
    String trimmed = accountIdentifier.trim();

    try {
      Long numericId = Long.valueOf(trimmed);
      return resolveNumericAccountId(numericId, accountType);
    } catch (NumberFormatException ignored) {
      // not numeric → treat as external id
    }

    PortfolioAccountType type = PortfolioAccountType.fromInt(accountType != null ? accountType : 2);
    org.apache.fineract.infrastructure.core.domain.ExternalId extId =
        externalIdFactory.create(trimmed);

    if (type == PortfolioAccountType.SAVINGS) {
      Long id = savingsAccountRepositoryWrapper.findIdByExternalId(extId);
      SavingsAccount sa = savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(id);
      if (sa == null) {
        throw new IllegalArgumentException("Savings account not found for external ID: " + trimmed);
      }
      log.info("FEE_COLLECT: Resolved savings externalId={} → id={}", trimmed, sa.getId());
      return sa.getId();
    } else if (type == PortfolioAccountType.LOAN) {
      var loan = loanAssembler.assembleFrom(extId);
      log.info("FEE_COLLECT: Resolved loan externalId={} → id={}", trimmed, loan.getId());
      return loan.getId();
    }
    throw new IllegalArgumentException("Unsupported account type: " + accountType);
  }

  private Long resolveNumericAccountId(Long numericId, Integer accountType) {
    PortfolioAccountType type = PortfolioAccountType.fromInt(accountType != null ? accountType : 2);
    if (type == PortfolioAccountType.SAVINGS) {
      return savingsAccountAssembler.assembleFrom(numericId, false).getId();
    } else if (type == PortfolioAccountType.LOAN) {
      return loanAssembler.assembleFrom(numericId).getId();
    }
    throw new IllegalArgumentException("Unsupported numeric account type: " + accountType);
  }

  private JsonCommand createJsonCommand(String json) {
    JsonElement parsed = fromApiJsonHelper.parse(json);
    return JsonCommand.from(
        json,
        parsed,
        fromApiJsonHelper,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
