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
import java.time.format.DateTimeFormatter;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

  /**
   * Fallback payment type id used only when {@code fee_payment_type_id} is not configured in
   * {@code SELF_SERVICE_FEE_CONFIG}. Prefer configuring the real ID of the payment type named "FEE".
   */
  private static final long DEFAULT_FEE_PAYMENT_TYPE_ID = 1L;

  /** Entity code embedded in SINPE-style references (Apolo = 373). */
  private static final String SINPE_ENTITY_CODE = "373";

  /** Channel / product segment used in generated fee references (matches live PIN pattern). */
  private static final String SINPE_CHANNEL_SEGMENT = "2201";

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
  private final JdbcTemplate jdbcTemplate;
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
      log.info("FEE_COLLECT [DEBUG]: SELF_SERVICE_FEE_CONFIG = {}", feeCfg);

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
      log.info("FEE_COLLECT [DEBUG]: INSTITUTION_ACCOUNT_FOR_FEES = {}", instCfg);

      // ---------------------------------------------------------------
      // 3. Resolve fee row
      // ---------------------------------------------------------------
      String currency =
          StringUtils.isNotBlank(req.getCurrencyCode()) ? req.getCurrencyCode() : "CRC";
      String transferMode =
          StringUtils.isNotBlank(req.getTransferMode()) ? req.getTransferMode() : "INSTANT";

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
      log.info(
          "FEE_COLLECT [DEBUG]: feeConfig id={}, type={}, feeType={}, feeValue={}, feeCurrency={}",
          feeConfig.getId(),
          feeConfig.getTransferType(),
          feeConfig.getFeeType(),
          feeConfig.getFeeValue(),
          feeConfig.getFeeCurrency());

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
      log.info(
          "FEE_COLLECT [DEBUG]: Resolved fromAccountId={} from identifier={} (type={})",
          fromAccountId,
          req.getFromAccount(),
          req.getFromAccountType());

      // Resolve payment type that must appear as "FEE" in paymentDetailData
      Long feePaymentTypeId = resolveFeePaymentTypeId(feeCfg);
      log.info(
          "FEE_COLLECT [DEBUG]: Resolved feePaymentTypeId={} (source config key 'fee_payment_type_id' or default {})",
          feePaymentTypeId,
          DEFAULT_FEE_PAYMENT_TYPE_ID);

      // ★ SINPE-like reference for the FEE ledger row (routingCode / receiptNumber)
      // Prefer parent PIN/SINPE routing + 1; otherwise generate a new SINPE-style id.
      String sinpeLikeRef = resolveSinpeLikeFeeReference(req, fromAccountId);
      log.info("FEE_COLLECT [DEBUG]: SINPE-like fee reference = {}", sinpeLikeRef);

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
      cmd.put(
          "transferDescription",
          StringUtils.isNotBlank(feeDescription)
              ? feeDescription
              : String.format("Comisión (%s %s)", feeAmount.toPlainString(), currency));
      cmd.put("locale", req.getLocale());
      cmd.put("dateFormat", req.getDateFormat());

      // ★ Force the FEE payment type (instead of the default SAME_BANK)
      cmd.put("paymentTypeId", feePaymentTypeId);

      // ★ Persist SINPE-like reference on payment detail so details API can surface it
      cmd.put("routingCode", sinpeLikeRef);
      cmd.put("receiptNumber", sinpeLikeRef);
      // optional: keeps checkNumber empty; some UIs use it as secondary ref
      cmd.put("accountNumber", "");
      cmd.put("checkNumber", "");
      cmd.put("bankNumber", "");

      // ---- DEBUG: dump the exact payload that will be sent ----
      log.info("FEE_COLLECT [DEBUG]: Full command map before serialization = {}", cmd);
      log.info(
          "FEE_COLLECT [DEBUG]: paymentTypeId present in map? {} → value={}",
          cmd.containsKey("paymentTypeId"),
          cmd.get("paymentTypeId"));
      log.info(
          "FEE_COLLECT [DEBUG]: routingCode/receiptNumber (SINPE-like ref) = {}",
          sinpeLikeRef);

      String json = gson.toJson(cmd);
      log.info("FEE_COLLECT [DEBUG]: Serialized JSON payload = {}", json);

      JsonCommand command = createJsonCommand(json);

      // Extra verification that JsonCommand actually carries the parameter
      boolean hasPaymentTypeId =
          command.parameterExists("paymentTypeId")
              || (command.json() != null && command.json().contains("\"paymentTypeId\""));
      log.info(
          "FEE_COLLECT [DEBUG]: JsonCommand contains paymentTypeId? {} | raw json snippet check={}",
          command.parameterExists("paymentTypeId"),
          hasPaymentTypeId);
      if (command.parameterExists("paymentTypeId")) {
        log.info(
            "FEE_COLLECT [DEBUG]: JsonCommand.longValueOfParameterNamed(\"paymentTypeId\") = {}",
            command.longValueOfParameterNamed("paymentTypeId"));
      }
      if (command.parameterExists("routingCode")) {
        log.info(
            "FEE_COLLECT [DEBUG]: JsonCommand routingCode = {}",
            command.stringValueOfParameterNamed("routingCode"));
      }

      log.info(
          "FEE_COLLECT: Executing commission transfer of {} {} (paymentTypeId={}, routingCode={})",
          feeAmount,
          currency,
          feePaymentTypeId,
          sinpeLikeRef);

      CommandProcessingResult result = accountTransfersWritePlatformService.create(command);

      if (result != null && result.getResourceId() != null) {
        log.info(
            "FEE_COLLECT: Commission collected. txnId={}, amount={} {} | paymentTypeId used={} | ref={}",
            result.getResourceId(),
            feeAmount,
            currency,
            feePaymentTypeId,
            sinpeLikeRef);
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

  /**
   * Builds the SINPE-style reference stored on the FEE payment detail.
   *
   * <ol>
   *   <li>If {@link FeeCollectionRequest} exposes a parent routing / external ref → parent sequence
   *       + 1 (e.g. {@code …4900} → {@code …4901}).
   *   <li>Else look up the latest PIN/SINPE/SINPE_MOVIL routing on the same source account today.
   *   <li>Else generate a new reference: {@code yyyyMMdd + entity + channel + 10-digit sequence}.
   * </ol>
   */
  private String resolveSinpeLikeFeeReference(FeeCollectionRequest req, Long fromAccountId) {
    // 1) Explicit parent from request (if the DTO carries it)
    String parentFromReq = extractParentRoutingFromRequest(req);
    if (StringUtils.isNotBlank(parentFromReq)) {
      String next = incrementSinpeSequence(parentFromReq.trim());
      log.info(
          "FEE_COLLECT: Fee ref from request parent routing {} → {}", parentFromReq, next);
      return next;
    }

    // 2) Latest same-day PIN/SINPE routing on this savings account
    if (fromAccountId != null) {
      String parentFromDb = findLatestParentRoutingCode(fromAccountId);
      if (StringUtils.isNotBlank(parentFromDb)) {
        String next = incrementSinpeSequence(parentFromDb.trim());
        log.info(
            "FEE_COLLECT: Fee ref from DB parent routing {} → {}", parentFromDb, next);
        return next;
      }
    }

    // 3) Standalone generated reference
    String generated = generateSinpeLikeReference();
    log.info("FEE_COLLECT: Fee ref generated (no parent found) = {}", generated);
    return generated;
  }

  /**
   * Best-effort extraction of a parent routing / external reference from the request DTO without
   * hard-coupling to a specific field name (supports several common getter names).
   */
  private String extractParentRoutingFromRequest(FeeCollectionRequest req) {
    if (req == null) {
      return null;
    }
    String[] candidates = {
      "getParentRoutingCode",
      "getRoutingCode",
      "getExternalReference",
      "getParentExternalId",
      "getChannelRefNumber",
      "getSinpeRefNumber"
    };
    for (String methodName : candidates) {
      try {
        Object value = req.getClass().getMethod(methodName).invoke(req);
        if (value != null && StringUtils.isNotBlank(value.toString())) {
          return value.toString().trim();
        }
      } catch (NoSuchMethodException ignored) {
        // field not present on this DTO version
      } catch (Exception e) {
        log.debug("FEE_COLLECT: Could not read {} from request: {}", methodName, e.getMessage());
      }
    }
    return null;
  }

  /**
   * Finds the most recent PIN / SINPE / SINPE_MOVIL routing_code on the same savings account for
   * today's tenant date (parent transfer that triggered this fee).
   */
  private String findLatestParentRoutingCode(Long fromAccountId) {
    LocalDate today = DateUtils.getLocalDateOfTenant();
    final String sql =
        "SELECT pd.routing_code "
            + "FROM m_savings_account_transaction sat "
            + "INNER JOIN m_payment_detail pd ON pd.id = sat.payment_detail_id "
            + "INNER JOIN m_payment_type pt ON pt.id = pd.payment_type_id "
            + "WHERE sat.savings_account_id = ? "
            + "  AND sat.transaction_date = ? "
            + "  AND UPPER(pt.value) IN ('PIN', 'SINPE', 'SINPE_MOVIL') "
            + "  AND pd.routing_code IS NOT NULL "
            + "  AND TRIM(pd.routing_code) <> '' "
            + "ORDER BY sat.id DESC "
            + "LIMIT 1";
    try {
      return jdbcTemplate.queryForObject(sql, String.class, fromAccountId, today);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      return null;
    } catch (Exception e) {
      log.warn(
          "FEE_COLLECT: findLatestParentRoutingCode failed for accountId={}: {}",
          fromAccountId,
          e.getMessage());
      return null;
    }
  }

  /**
   * Increments the trailing numeric sequence of a SINPE-style routing code.
   * {@code 2026102137322010000004900} → {@code 2026102137322010000004901}
   */
  private static String incrementSinpeSequence(String routingCode) {
    if (routingCode == null || routingCode.isBlank()) {
      return "";
    }
    String code = routingCode.trim();
    int i = code.length() - 1;
    while (i >= 0 && Character.isDigit(code.charAt(i))) {
      i--;
    }
    String prefix = code.substring(0, i + 1);
    String seqStr = code.substring(i + 1);
    if (seqStr.isEmpty()) {
      return code;
    }
    try {
      long seq = Long.parseLong(seqStr);
      long next = seq + 1L;
      String nextStr = String.format("%0" + seqStr.length() + "d", next);
      return prefix + nextStr;
    } catch (NumberFormatException e) {
      return code;
    }
  }

  /**
   * Generates a standalone SINPE-like reference when no parent transfer is available.
   * Format: {@code yyyyMMdd + entityCode + channelSegment + 10-digit sequence}
   * Example: {@code 2026102137322010000012345}
   */
  private String generateSinpeLikeReference() {
    LocalDate today = DateUtils.getLocalDateOfTenant();
    String datePart = today.format(DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd
    // Use low-order millis for a reasonably unique 10-digit suffix
    long suffix = System.currentTimeMillis() % 10_000_000_000L;
    return datePart
        + SINPE_ENTITY_CODE
        + SINPE_CHANNEL_SEGMENT
        + String.format("%010d", suffix);
  }

  /**
   * Resolves the payment type id that should be attached to fee transfers so that
   * {@code paymentDetailData.paymentType.name} becomes {@code "FEE"}.
   *
   * <p>Priority:
   * <ol>
   *   <li>{@code fee_payment_type_id} from tenant property set {@code SELF_SERVICE_FEE_CONFIG}
   *   <li>Hard-coded fallback {@value #DEFAULT_FEE_PAYMENT_TYPE_ID}
   * </ol>
   */
  private Long resolveFeePaymentTypeId(Map<String, String> feeCfg) {
    String configured = feeCfg != null ? feeCfg.get("fee_payment_type_id") : null;
    log.info(
        "FEE_COLLECT [DEBUG]: resolveFeePaymentTypeId → raw config value='{}'", configured);

    if (StringUtils.isNotBlank(configured)) {
      try {
        long id = Long.parseLong(configured.trim());
        if (id > 0) {
          log.info("FEE_COLLECT: Using configured fee_payment_type_id={}", id);
          return id;
        }
        log.warn(
            "FEE_COLLECT: Invalid fee_payment_type_id='{}'. Falling back to default {}.",
            configured,
            DEFAULT_FEE_PAYMENT_TYPE_ID);
      } catch (NumberFormatException e) {
        log.warn(
            "FEE_COLLECT: Non-numeric fee_payment_type_id='{}'. Falling back to default {}.",
            configured,
            DEFAULT_FEE_PAYMENT_TYPE_ID);
      }
    } else {
      log.info(
          "FEE_COLLECT: fee_payment_type_id not configured. Using default {}.",
          DEFAULT_FEE_PAYMENT_TYPE_ID);
    }
    return DEFAULT_FEE_PAYMENT_TYPE_ID;
  }

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