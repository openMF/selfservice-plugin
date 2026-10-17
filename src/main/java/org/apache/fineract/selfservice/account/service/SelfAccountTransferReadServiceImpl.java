/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.account.service;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAuditRepository;
import org.apache.fineract.selfservice.account.exception.SelfAccountTransferTransactionNotFoundException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferReadServiceImpl implements SelfAccountTransferReadService {

  private final JdbcTemplate jdbcTemplate;
  private final PlatformSelfServiceSecurityContext context;
  private final SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
  private final SelfServiceSameBankTransferAuditRepository sameBankTransferAuditRepository;
  private final PinExternalTransferService pinExternalTransferService;
  private final SinpeExternalApiClient sinpeExternalApiClient;
  private final Gson gson = new Gson();

  @Override
  public Collection<SelfAccountTemplateData> retrieveSelfAccountTemplateData(
          AppSelfServiceUser user) {
    SelfAccountTemplateMapper mapper = new SelfAccountTemplateMapper();
    StringBuilder sql =
            new StringBuilder()
                    .append("select s.id as accountId, ")
                    .append("s.account_no as accountNo, ")
                    .append("2 as accountType, ")
                    .append("c.id as clientId, ")
                    .append("c.display_name as clientName, ")
                    .append("o.id as officeId, ")
                    .append("o.name as officeName ")
                    .append("from m_appselfservice_user as u ")
                    .append("inner join m_selfservice_user_client_mapping as map on u.id = map.appuser_id ")
                    .append("inner join m_client as c on map.client_id = c.id ")
                    .append("inner join m_office as o on c.office_id = o.id ")
                    .append("inner join m_savings_account as s on s.client_id = c.id ")
                    .append("where u.id = ? ")
                    .append("and s.status_enum = 300 ")
                    .append("union ")
                    .append("select l.id as accountId, ")
                    .append("l.account_no as accountNo, ")
                    .append("1 as accountType, ")
                    .append("c.id as clientId, ")
                    .append("c.display_name as clientName, ")
                    .append("o.id as officeId, ")
                    .append("o.name as officeName ")
                    .append("from m_appselfservice_user as u ")
                    .append("inner join m_selfservice_user_client_mapping as map on u.id = map.appuser_id ")
                    .append("inner join m_client as c on map.client_id = c.id ")
                    .append("inner join m_office as o on c.office_id = o.id ")
                    .append("inner join m_loan as l on l.client_id = c.id ")
                    .append("where u.id = ? ")
                    .append("and l.loan_status_id = 300 ");
    return this.jdbcTemplate.query(
            sql.toString(), mapper, new Object[] {user.getId(), user.getId()});
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, Object> retrieveTransactionDetails(
          Long accountId, String txnId, String transferType) {

    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SavingsAccount savingsAccount =
            this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId);

    if (!savingsAccount.getClient().getId().equals(client.getId())) {
      log.error(
              "SECURITY ALERT: User {} attempted to access account {} not belonging to them.",
              user.getId(),
              accountId);
      throw accountAccessValidationError(accountId);
    }

    log.info(
            "FETCH DETAILS: Querying details for account: {}, txnId: {}, transferType: {}",
            accountId,
            txnId,
            transferType);

    Map<String, Object> rawData;
    if ("Apolo".equalsIgnoreCase(transferType) || "SAME_BANK".equalsIgnoreCase(transferType)) {
      rawData = processApoloTransfer(client.getId(), accountId, txnId, transferType);
    } else if ("PIN".equalsIgnoreCase(transferType)
            || "SINPE".equalsIgnoreCase(transferType)
            || "SINPE_MOVIL".equalsIgnoreCase(transferType)) {
      rawData = processExternalTransfer(accountId, txnId, transferType);
    } else {
      throw unsupportedTransferTypeValidationError(transferType);
    }

    BigDecimal fallbackAmount =
            rawData.containsKey("debitedAmount") && rawData.get("debitedAmount") != null
                    ? new BigDecimal(rawData.get("debitedAmount").toString())
                    : BigDecimal.ZERO;
    String fallbackCurrency = rawData.getOrDefault("debitCurrencyCode", "CRC").toString();

    Map<String, Object> homologatedData =
            homologateResponseData(rawData, fallbackAmount, fallbackCurrency, transferType);

    Map<String, Object> response = new HashMap<>();
    response.put("transferType", transferType.toUpperCase());
    response.put("data", homologatedData);
    return response;
  }

  private Map<String, Object> processApoloTransfer(
          Long clientId, Long accountId, String txnId, String transferType) {

    Optional<SelfServiceSameBankTransferAudit> auditOpt =
            this.sameBankTransferAuditRepository.findAuditDetail(clientId, accountId, txnId);

    if (auditOpt.isPresent()) {
      return mapAuditToRawData(auditOpt.get());
    }

    Map<String, Object> fromLedger = loadSameBankFromSavingsTransaction(accountId, txnId);
    if (fromLedger != null) {
      log.info(
              "FETCH DETAILS: SAME_BANK resolved from savings transaction (no audit row)"
                      + " accountId={}, txnId={}",
              accountId,
              txnId);
      return fromLedger;
    }

    throw new SelfAccountTransferTransactionNotFoundException(accountId, txnId, transferType);
  }

  private Map<String, Object> mapAuditToRawData(SelfServiceSameBankTransferAudit audit) {
    Map<String, Object> rawData = new HashMap<>();
    rawData.put("operationId", audit.getOperationId());
    rawData.put("internalRefNumber", audit.getInternalRefNumber());
    rawData.put("channelRefNumber", audit.getInternalRefNumber());
    rawData.put("sinpeRefNumber", "");
    rawData.put("debitedAmount", audit.getTransferAmount());
    rawData.put("debitCurrencyCode", audit.getCurrencyCode());
    rawData.put(
            "commissionAmount",
            audit.getFeeAmount() != null ? audit.getFeeAmount() : BigDecimal.ZERO);
    rawData.put("commissionCurrency", audit.getCurrencyCode());
    rawData.put("exchangeRate", 0);
    rawData.put(
            "registrationDate",
            audit.getRegistrationDate() != null ? audit.getRegistrationDate().toString() : "");
    rawData.put(
            "processingDate",
            audit.getProcessingDate() != null ? audit.getProcessingDate().toString() : "");
    rawData.put("stateDescription", audit.getStateDescription());
    rawData.put("successful", audit.isSuccessful());
    rawData.put("stateCode", audit.isSuccessful() ? 32 : 128);
    rawData.put(
            "rejectDescription",
            audit.getRejectDescription() != null ? audit.getRejectDescription() : "");

    Map<String, Object> destinationCustomer =
            getDestinationCustomerInfoByAccount(audit.getToAccountIdentifier());
    Map<String, Object> originCustomer =
            getOriginCustomerInfoByAccount(audit.getFromAccountIdentifier());

    Map<String, String> bankDetails = resolveDestinationDetails(audit.getToAccountIdentifier());
    destinationCustomer.put("entityCode", bankDetails.getOrDefault("entityCode", ""));
    destinationCustomer.put("entityName", bankDetails.getOrDefault("entityName", ""));
    if (isStringBlank(destinationCustomer.get("name"))) {
      destinationCustomer.put("name", bankDetails.getOrDefault("holder", ""));
    }
    if (isStringBlank(destinationCustomer.get("id"))) {
      destinationCustomer.put("id", bankDetails.getOrDefault("holderId", ""));
    }

    Map<String, Object> customData = new HashMap<>();
    customData.put("fromAccountIdentifier", audit.getFromAccountIdentifier());
    customData.put("toAccountIdentifier", audit.getToAccountIdentifier());
    customData.put("transferDescription", audit.getTransferDescription());
    customData.put("reference", audit.getReference());
    customData.put("originCustomer", originCustomer);
    customData.put("destinationCustomer", destinationCustomer);
    rawData.put("customData", customData);
    return rawData;
  }

  private Map<String, Object> loadSameBankFromSavingsTransaction(Long accountId, String txnId) {
    final String sql =
            "SELECT "
                    + "  sat.id                    AS sat_id, "
                    + "  sat.amount                AS amount, "
                    + "  sat.transaction_date      AS transaction_date, "
                    + "  sat.created_date          AS created_date, "
                    + "  sat.is_reversed           AS is_reversed, "
                    + "  sat.ref_no                AS ref_no, "
                    + "  sa.currency_code          AS currency_code, "
                    + "  sa.account_no             AS from_account_no, "
                    + "  COALESCE(NULLIF(sa.external_id, ''), sa.account_no) AS from_iban, "
                    + "  pd.id                     AS payment_detail_id, "
                    + "  pd.account_number         AS account_number, "
                    + "  pd.check_number           AS check_number, "
                    + "  pd.routing_code           AS routing_code, "
                    + "  pd.receipt_number         AS receipt_number, "
                    + "  pd.bank_number            AS bank_number, "
                    + "  pt.value                  AS payment_type_name "
                    + "FROM m_savings_account_transaction sat "
                    + "INNER JOIN m_savings_account sa ON sa.id = sat.savings_account_id "
                    + "LEFT JOIN m_payment_detail pd ON pd.id = sat.payment_detail_id "
                    + "LEFT JOIN m_payment_type pt ON pt.id = pd.payment_type_id "
                    + "WHERE sat.savings_account_id = ? "
                    + "  AND ( "
                    + "        CAST(sat.id AS VARCHAR) = ? "
                    + "     OR sat.ref_no = ? "
                    + "     OR CAST(pd.id AS VARCHAR) = ? "
                    + "     OR pd.receipt_number = ? "
                    + "     OR pd.routing_code = ? "
                    + "  ) "
                    + "ORDER BY sat.id DESC "
                    + "LIMIT 1";

    try {
      List<Map<String, Object>> rows =
              this.jdbcTemplate.query(
                      sql,
                      (rs, rowNum) -> mapLedgerRow(rs, accountId),
                      accountId,
                      txnId,
                      txnId,
                      txnId,
                      txnId,
                      txnId);

      if (rows == null || rows.isEmpty() || rows.get(0) == null) {
        return null;
      }
      return rows.get(0);
    } catch (Exception e) {
      log.warn(
              "SAME_BANK ledger fallback failed for accountId={}, txnId={}: {}",
              accountId,
              txnId,
              e.getMessage());
      return null;
    }
  }

  private Map<String, Object> mapLedgerRow(ResultSet rs, Long accountId) throws SQLException {
    boolean reversed = rs.getBoolean("is_reversed");
    BigDecimal amount = rs.getBigDecimal("amount");
    String currency = rs.getString("currency_code");
    String routing = nullToEmpty(rs.getString("routing_code"));
    String receipt = nullToEmpty(rs.getString("receipt_number"));
    String checkNumber = nullToEmpty(rs.getString("check_number"));
    String toAccountNumber = nullToEmpty(rs.getString("account_number"));
    String bankNumber = nullToEmpty(rs.getString("bank_number"));
    String refNo = nullToEmpty(rs.getString("ref_no"));
    String fromIban = nullToEmpty(rs.getString("from_iban"));
    String paymentType = nullToEmpty(rs.getString("payment_type_name"));
    long satId = rs.getLong("sat_id");

    String internalRef =
            !receipt.isEmpty()
                    ? receipt
                    : (!routing.isEmpty()
                            ? routing
                            : (!refNo.isEmpty() ? refNo : String.valueOf(satId)));

    java.sql.Timestamp created = rs.getTimestamp("created_date");
    java.sql.Date txnDate = rs.getDate("transaction_date");
    String registrationDate =
            created != null
                    ? created.toInstant().toString()
                    : (txnDate != null ? txnDate.toLocalDate().toString() : "");
    String processingDate =
            txnDate != null ? txnDate.toLocalDate().toString() : registrationDate;

    Map<String, Object> destinationCustomer =
            getDestinationCustomerInfoByAccount(
                    !toAccountNumber.isEmpty() ? toAccountNumber : null);

    String targetAccount = !toAccountNumber.isEmpty() ? toAccountNumber : fromIban;
    Map<String, String> bankDetails = resolveDestinationDetails(targetAccount);

    destinationCustomer.put(
            "entityCode",
            !bankNumber.isEmpty() ? bankNumber : bankDetails.getOrDefault("entityCode", ""));
    destinationCustomer.put("entityName", bankDetails.getOrDefault("entityName", ""));
    if (isStringBlank(destinationCustomer.get("name"))) {
      destinationCustomer.put("name", bankDetails.getOrDefault("holder", ""));
    }
    if (isStringBlank(destinationCustomer.get("id"))) {
      destinationCustomer.put("id", bankDetails.getOrDefault("holderId", ""));
    }

    String fromAccount = !fromIban.isEmpty() ? fromIban : String.valueOf(accountId);
    Map<String, Object> originCustomer = getOriginCustomerInfoByAccount(fromAccount);

    Map<String, Object> customData = new HashMap<>();
    customData.put(
            "fromAccountIdentifier", !fromIban.isEmpty() ? fromIban : String.valueOf(accountId));
    customData.put("toAccountIdentifier", toAccountNumber);
    customData.put(
            "transferDescription", !paymentType.isEmpty() ? paymentType : "SAME_BANK");
    customData.put("reference", !checkNumber.isEmpty() ? checkNumber : internalRef);
    customData.put("originCustomer", originCustomer);
    customData.put("destinationCustomer", destinationCustomer);
    customData.put("savingsTransactionId", satId);
    customData.put("paymentDetailId", rs.getObject("payment_detail_id"));
    customData.put("routingCode", routing);
    customData.put("receiptNumber", receipt);

    Map<String, Object> rawData = new HashMap<>();
    rawData.put("operationId", String.valueOf(satId));
    rawData.put("internalRefNumber", internalRef);
    rawData.put("channelRefNumber", internalRef);
    rawData.put("sinpeRefNumber", "");
    rawData.put("debitedAmount", amount != null ? amount : BigDecimal.ZERO);
    rawData.put("debitCurrencyCode", currency != null ? currency : "CRC");
    rawData.put("commissionAmount", BigDecimal.ZERO);
    rawData.put("commissionCurrency", currency != null ? currency : "CRC");
    rawData.put("exchangeRate", 0);
    rawData.put("registrationDate", registrationDate);
    rawData.put("processingDate", processingDate);
    rawData.put("stateDescription", reversed ? "Reversed" : "Completed");
    rawData.put("successful", !reversed);
    rawData.put("stateCode", reversed ? 128 : 32);
    rawData.put("rejectDescription", reversed ? "Transaction reversed" : "");
    rawData.put("customData", customData);
    return rawData;
  }

  private Map<String, String> resolveDestinationDetails(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return new HashMap<>();
    }
    String cleanIdentifier = identifier.trim();
    if (cleanIdentifier.toUpperCase().startsWith("CR")) {
      return resolveBankDetailsFromAccount(cleanIdentifier);
    }
    return resolveBankDetailsFromPhone(cleanIdentifier);
  }

  private Map<String, String> resolveBankDetailsFromAccount(String accountIdentifier) {
    Map<String, String> details = new HashMap<>();
    try {
      String jsonResponse = this.pinExternalTransferService.getAccountInfo(accountIdentifier);
      if (jsonResponse != null && !jsonResponse.contains("\"error\"")) {
        Map<String, Object> infoMap = this.gson.fromJson(jsonResponse, Map.class);
        if (infoMap != null) {
          details.put(
                  "entityCode",
                  infoMap.get("entityCode") != null ? infoMap.get("entityCode").toString() : "");
          details.put(
                  "entityName",
                  infoMap.get("entityName") != null ? infoMap.get("entityName").toString() : "");
          details.put(
                  "holder", infoMap.get("holder") != null ? infoMap.get("holder").toString() : "");
          details.put(
                  "holderId",
                  infoMap.get("holderId") != null ? infoMap.get("holderId").toString() : "");
        }
      }
    } catch (Exception e) {
      log.warn("Could not fetch account info for IBAN: {}", accountIdentifier, e);
    }
    return details;
  }

  private Map<String, String> resolveBankDetailsFromPhone(String phoneNumber) {
    Map<String, String> details = new HashMap<>();
    try {
      log.info("Querying Kindo-SAPI SINPE Phone Info for: {}", phoneNumber);
      String jsonResponse = this.sinpeExternalApiClient.getPhoneInfo(phoneNumber);
      if (jsonResponse != null && !jsonResponse.isBlank() && !jsonResponse.contains("\"error\"")) {
        Map<String, Object> phoneData = this.gson.fromJson(jsonResponse, Map.class);
        if (phoneData != null) {
          details.put(
                  "entityCode",
                  phoneData.get("entityCode") != null
                          ? phoneData.get("entityCode").toString()
                          : "");
          details.put(
                  "entityName",
                  phoneData.get("entityName") != null
                          ? phoneData.get("entityName").toString()
                          : "");
          details.put(
                  "holder",
                  phoneData.get("holder") != null ? phoneData.get("holder").toString() : "");
          details.put(
                  "holderId",
                  phoneData.get("holderId") != null
                          ? phoneData.get("holderId").toString()
                          : "");
        }
      }
    } catch (Exception e) {
      log.warn("Could not fetch phone info for Phone: {}", phoneNumber, e);
    }
    return details;
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v.trim();
  }

  private static boolean isStringBlank(Object obj) {
    return obj == null || obj.toString().trim().isEmpty();
  }

  // ---------------------------------------------------------------------------
  // EXTERNAL (PIN / SINPE / SINPE_MOVIL) – internal Fineract fallback
  // ---------------------------------------------------------------------------

  private Map<String, Object> processExternalTransfer(
          Long accountId, String txnId, String transferType) {

    String externalRef = getExternalReceiptNumber(accountId, txnId);

    if (externalRef == null || externalRef.isEmpty()) {
      Map<String, Object> fromLedger =
              loadExternalFromSavingsTransaction(accountId, txnId, transferType, null);
      if (fromLedger != null) {
        log.info(
                "FETCH DETAILS: {} resolved from internal ledger (no external ref) accountId={} txnId={}",
                transferType,
                accountId,
                txnId);
        return fromLedger;
      }
      throw new SelfAccountTransferTransactionNotFoundException(accountId, txnId, transferType);
    }

    try {
      if ("PIN".equalsIgnoreCase(transferType)) {
        log.info("Querying PIN external service with receiptRef: {}", externalRef);
        String pinResponseJson = this.pinExternalTransferService.getTransactionDetail(externalRef);
        return this.gson.fromJson(pinResponseJson, Map.class);
      } else {
        log.info("Querying SINPE external service with receiptRef: {}", externalRef);
        Object sinpeResponse = this.sinpeExternalApiClient.getTransactionDetail(externalRef);
        String sinpeJson =
                sinpeResponse instanceof String
                        ? (String) sinpeResponse
                        : this.gson.toJson(sinpeResponse);
        return this.gson.fromJson(sinpeJson, Map.class);
      }
    } catch (SelfAccountTransferTransactionNotFoundException e) {
      throw e;
    } catch (Exception e) {
      log.warn(
              "External {} lookup failed for ref={} ({}). Falling back to internal ledger. accountId={} txnId={}",
              transferType,
              externalRef,
              e.getMessage(),
              accountId,
              txnId);

      Map<String, Object> fromLedger =
              loadExternalFromSavingsTransaction(accountId, txnId, transferType, externalRef);
      if (fromLedger != null) {
        log.info(
                "FETCH DETAILS: {} resolved from internal ledger after external failure accountId={} txnId={} ref={}",
                transferType,
                accountId,
                txnId,
                externalRef);
        return fromLedger;
      }

      log.error("Error querying {} transaction detail for ref: {}", transferType, externalRef, e);
      throw externalServiceValidationError(transferType, externalRef, e);
    }
  }

  /**
   * Internal fallback using ledger + m_sinpe_participant + VALIDA_CREDITO / VALIDA_DEBITO.
   *
   * <p>Credit (transaction_type_enum=1): origin = external sender from VALIDA_CREDITO
   * (NOMBRE_ORIGEN, IDENTIFICACION) + bank from pd.bank_number; destination = local client.
   * Debit (transaction_type_enum=2): origin = local; destination = external.
   */
  private Map<String, Object> loadExternalFromSavingsTransaction(
          Long accountId, String txnId, String transferType, String knownExternalRef) {

    final String sql =
            "SELECT "
                    + "  sat.id                      AS sat_id, "
                    + "  sat.transaction_type_enum   AS transaction_type_enum, "
                    + "  sat.amount                  AS amount, "
                    + "  sat.transaction_date        AS transaction_date, "
                    + "  sat.created_date            AS created_date, "
                    + "  sat.is_reversed             AS is_reversed, "
                    + "  sat.ref_no                  AS ref_no, "
                    + "  sa.currency_code            AS currency_code, "
                    + "  sa.account_no               AS from_account_no, "
                    + "  COALESCE(NULLIF(sa.external_id, ''), sa.account_no) AS local_iban, "
                    + "  c.display_name              AS local_client_name, "
                    + "  c.fullname                  AS local_fullname, "
                    + "  COALESCE(c.email_address, '') AS local_email, "
                    + "  COALESCE( "
                    + "    (SELECT ci.document_key FROM m_client_identifier ci "
                    + "      WHERE ci.client_id = c.id ORDER BY ci.id LIMIT 1), '') AS local_document, "
                    + "  pd.id                       AS payment_detail_id, "
                    + "  pd.account_number           AS pd_account_number, "
                    + "  pd.check_number             AS check_number, "
                    + "  pd.routing_code             AS routing_code, "
                    + "  pd.receipt_number           AS receipt_number, "
                    + "  pd.bank_number              AS bank_number, "
                    + "  pt.value                    AS payment_type_name, "
                    + "  sp_cp.code                  AS cp_code, "
                    + "  sp_cp.description           AS cp_name, "
                    + "  sp_local.code               AS local_code, "
                    + "  sp_local.description        AS local_entity_name, "
                    + "  vc.\"IDENTIFICACION\"         AS vc_identificacion, "
                    + "  vc.\"CUENTA_IBAN\"            AS vc_iban, "
                    + "  vc.\"ID_RELACION_CLIENTE\"    AS vc_relacion, "
                    + "  vc.\"NOMBRE_ORIGEN\"          AS vc_nombre_origen, "
                    + "  vd.\"IDENTIFICACION\"         AS vd_identificacion, "
                    + "  vd.\"CUENTA_IBAN\"            AS vd_iban, "
                    + "  vd.\"CODIGO_REFERENCIA\"      AS vd_ref "
                    + "FROM m_savings_account_transaction sat "
                    + "INNER JOIN m_savings_account sa ON sa.id = sat.savings_account_id "
                    + "INNER JOIN m_client c ON c.id = sa.client_id "
                    + "LEFT JOIN m_payment_detail pd ON pd.id = sat.payment_detail_id "
                    + "LEFT JOIN m_payment_type pt ON pt.id = pd.payment_type_id "
                    + "LEFT JOIN m_sinpe_participant sp_cp "
                    + "       ON sp_cp.is_active = true "
                    + "      AND ( "
                    + "            sp_cp.code = TRIM(COALESCE(pd.bank_number, '')) "
                    + "         OR sp_cp.code = lpad(TRIM(COALESCE(pd.bank_number, '')), 4, '0') "
                    + "          ) "
                    + "LEFT JOIN m_sinpe_participant sp_local "
                    + "       ON sp_local.is_active = true "
                    + "      AND ( "
                    + "            sp_local.code = substring(replace(COALESCE(sa.external_id, ''), ' ', '') from 5 for 3) "
                    + "         OR sp_local.code = lpad(substring(replace(COALESCE(sa.external_id, ''), ' ', '') from 5 for 3), 4, '0') "
                    + "          ) "
                    // Match VALIDA by txn id OR receipt/routing (sibling txn 174 vs sat 173)
                    + "LEFT JOIN \"VALIDA_CREDITO\" vc "
                    + "       ON (vc.savings_transaction_id = sat.id "
                    + "        OR vc.\"ID_RELACION_CLIENTE\" = pd.receipt_number "
                    + "        OR vc.\"ID_RELACION_CLIENTE\" = pd.routing_code "
                    + "        OR vc.\"CHANNEL_REF_NUMBER\" = pd.routing_code) "
                    + "LEFT JOIN \"VALIDA_DEBITO\" vd "
                    + "       ON (vd.savings_transaction_id = sat.id "
                    + "        OR vd.\"CODIGO_REFERENCIA\" = pd.routing_code "
                    + "        OR vd.\"CHANNEL_REF_NUMBER\" = pd.routing_code) "
                    + "WHERE sat.savings_account_id = ? "
                    + "  AND ( "
                    + "        CAST(sat.id AS VARCHAR) = ? "
                    + "     OR sat.ref_no = ? "
                    + "     OR CAST(pd.id AS VARCHAR) = ? "
                    + "     OR pd.receipt_number = ? "
                    + "     OR pd.routing_code = ? "
                    + "  ) "
                    + "ORDER BY sat.id DESC "
                    + "LIMIT 1";

    try {
      List<Map<String, Object>> rows =
              this.jdbcTemplate.query(
                      sql,
                      (rs, rowNum) ->
                              mapLedgerRowForExternal(rs, accountId, transferType, knownExternalRef),
                      accountId,
                      txnId,
                      txnId,
                      txnId,
                      txnId,
                      txnId);

      if (rows == null || rows.isEmpty() || rows.get(0) == null) {
        return null;
      }
      return rows.get(0);
    } catch (Exception e) {
      log.warn(
              "{} internal ledger fallback failed for accountId={}, txnId={}: {} | cause={}",
              transferType,
              accountId,
              txnId,
              e.getMessage(),
              e.getCause() != null ? e.getCause().getMessage() : "n/a");
      return null;
    }
  }

  private Map<String, Object> mapLedgerRowForExternal(
          ResultSet rs, Long accountId, String transferType, String knownExternalRef)
          throws SQLException {

    boolean reversed = rs.getBoolean("is_reversed");
    BigDecimal amount = rs.getBigDecimal("amount");
    String currency = rs.getString("currency_code");
    long satId = rs.getLong("sat_id");
    int txnType = rs.getInt("transaction_type_enum"); // 1=DEPOSIT/credit, 2=WITHDRAWAL/debit
    boolean isCredit = txnType == 1;

    String routing = nullToEmpty(rs.getString("routing_code"));
    String receipt = nullToEmpty(rs.getString("receipt_number"));
    String refNo = nullToEmpty(rs.getString("ref_no"));

    String sinpeRef =
            firstNonBlank(
                    knownExternalRef,
                    routing,
                    nullToEmpty(rs.getString("vd_ref")),
                    receipt,
                    refNo,
                    String.valueOf(satId));

    java.sql.Timestamp created = rs.getTimestamp("created_date");
    java.sql.Date txnDate = rs.getDate("transaction_date");
    String registrationDate =
            created != null
                    ? created.toInstant().toString()
                    : (txnDate != null ? txnDate.toLocalDate().toString() : "");
    String processingDate =
            txnDate != null ? txnDate.toLocalDate().toString() : registrationDate;

    // Local (account owner)
    String localName =
            firstNonBlank(
                    nullToEmpty(rs.getString("local_client_name")),
                    nullToEmpty(rs.getString("local_fullname")));
    String localIban =
            firstNonBlank(
                    nullToEmpty(rs.getString("local_iban")),
                    nullToEmpty(rs.getString("from_account_no")),
                    nullToEmpty(rs.getString("pd_account_number")));
    String localDoc = nullToEmpty(rs.getString("local_document"));
    String localEmail = nullToEmpty(rs.getString("local_email"));
    String localEntityCode =
            stripLeadingZero(firstNonBlank(nullToEmpty(rs.getString("local_code")), "0137"));
    String localEntityName =
            firstNonBlank(nullToEmpty(rs.getString("local_entity_name")), "APOLO CAPITAL");

    // Counterparty bank (pd.bank_number 102 → 0102 BAC)
    String cpCodeRaw =
            firstNonBlank(
                    nullToEmpty(rs.getString("cp_code")),
                    padBankCode(nullToEmpty(rs.getString("bank_number"))));
    String cpCode = padBankCode(cpCodeRaw);
    String cpName = nullToEmpty(rs.getString("cp_name"));

    // VALIDA_CREDITO / VALIDA_DEBITO (priority for origin person on credits)
    String vcName = nullToEmpty(rs.getString("vc_nombre_origen"));
    String vcId = nullToEmpty(rs.getString("vc_identificacion"));
    String vcIban = nullToEmpty(rs.getString("vc_iban"));
    String vdId = nullToEmpty(rs.getString("vd_identificacion"));
    String vdIban = nullToEmpty(rs.getString("vd_iban"));

    Map<String, String> externalPerson = loadExternalPersonFromAudit(satId, sinpeRef, receipt);

    String extName =
            firstNonBlank(vcName, externalPerson.get("name"), "");
    String extId =
            firstNonBlank(
                    isCredit ? vcId : firstNonBlank(vdId, vcId),
                    externalPerson.get("id"),
                    "");
    // Origin IBAN is often unknown on incoming credits; do not use local CUENTA_IBAN as origin
    String extIban =
            firstNonBlank(
                    isCredit ? "" : firstNonBlank(vdIban, vcIban),
                    externalPerson.get("iban"),
                    "");
    String extEmail = firstNonBlank(externalPerson.get("email"), "");

    if (!extIban.isEmpty()) {
      Map<String, String> resolved = resolveDestinationDetails(extIban);
      if (extName.isEmpty()) {
        extName = nullToEmpty(resolved.get("holder"));
      }
      if (extId.isEmpty()) {
        extId = nullToEmpty(resolved.get("holderId"));
      }
      if (cpName.isEmpty()) {
        cpName = nullToEmpty(resolved.get("entityName"));
      }
      if (cpCode.isEmpty()) {
        cpCode = padBankCode(nullToEmpty(resolved.get("entityCode")));
      }
    }

    Map<String, Object> origin = new HashMap<>();
    Map<String, Object> dest = new HashMap<>();
    String fromAccount;
    String toAccount;

    if (isCredit) {
      // Incoming: origin = external sender, destination = local
      origin.put("name", extName);
      origin.put("iban", extIban);
      origin.put("id", extId);
      origin.put("email", extEmail);
      origin.put("entityCode", cpCode);
      origin.put("entityName", cpName);
      origin.put("idType", inferIdType(extId));
      origin.put("idTypeDescription", idTypeDescription(inferIdType(extId)));

      dest.put("name", localName);
      dest.put("iban", localIban);
      dest.put("id", localDoc);
      dest.put("email", localEmail);
      dest.put("entityCode", localEntityCode);
      dest.put("entityName", localEntityName);
      dest.put("idType", "2");
      dest.put("idTypeDescription", "Persona Jurídica Nacional");

      fromAccount = extIban;
      toAccount = localIban;
    } else {
      // Outgoing: origin = local, destination = external
      origin.put("name", localName);
      origin.put("iban", localIban);
      origin.put("id", localDoc);
      origin.put("email", localEmail);
      origin.put("entityCode", localEntityCode);
      origin.put("entityName", localEntityName);
      origin.put("idType", "2");
      origin.put("idTypeDescription", "Persona Jurídica Nacional");

      dest.put("name", extName);
      dest.put(
              "iban",
              firstNonBlank(extIban, nullToEmpty(rs.getString("pd_account_number"))));
      dest.put("id", extId);
      dest.put("email", extEmail);
      dest.put("entityCode", cpCode);
      dest.put("entityName", cpName);
      dest.put("idType", inferIdType(extId));
      dest.put("idTypeDescription", idTypeDescription(inferIdType(extId)));

      fromAccount = localIban;
      toAccount = (String) dest.get("iban");
    }

    String paymentType = nullToEmpty(rs.getString("payment_type_name"));

    Map<String, Object> customData = new HashMap<>();
    customData.put(
            "transferDescription",
            !paymentType.isEmpty() ? paymentType : transferType.toUpperCase());
    customData.put("reference", sinpeRef);
    customData.put("source", "INTERNAL_FALLBACK");
    customData.put("fromAccountIdentifier", fromAccount);
    customData.put("toAccountIdentifier", toAccount);
    customData.put("routingCode", routing);
    customData.put("receiptNumber", receipt);
    customData.put("savingsTransactionId", satId);
    customData.put("paymentDetailId", rs.getObject("payment_detail_id"));
    customData.put("originCustomer", origin);
    customData.put("destinationCustomer", dest);
    customData.put("direction", isCredit ? "CREDIT" : "DEBIT");

    Map<String, Object> rawData = new HashMap<>();
    rawData.put("operationId", String.valueOf(satId));
    rawData.put("internalRefNumber", sinpeRef);
    rawData.put("channelRefNumber", sinpeRef);
    rawData.put(
            "sinpeRefNumber",
            ("SINPE".equalsIgnoreCase(transferType) || "SINPE_MOVIL".equalsIgnoreCase(transferType))
                    ? sinpeRef
                    : "");
    rawData.put("debitedAmount", amount != null ? amount : BigDecimal.ZERO);
    rawData.put("debitCurrencyCode", currency != null ? currency : "CRC");
    rawData.put("commissionAmount", BigDecimal.ZERO);
    rawData.put("commissionCurrency", currency != null ? currency : "CRC");
    rawData.put("exchangeRate", 0);
    rawData.put("registrationDate", registrationDate);
    rawData.put("processingDate", processingDate);
    rawData.put("stateDescription", reversed ? "Reversed" : "Completed");
    rawData.put("successful", !reversed);
    rawData.put("stateCode", reversed ? 128 : 32);
    rawData.put("rejectDescription", reversed ? "Transaction reversed" : "");
    rawData.put("customData", customData);
    return rawData;
  }

  /** Parse m_sinpe_audit_log when VALIDA has no person fields. */
  private Map<String, String> loadExternalPersonFromAudit(
          long satId, String sinpeRef, String receipt) {
    Map<String, String> out = new HashMap<>();
    try {
      String sql =
              "SELECT request_xml, response_xml FROM m_sinpe_audit_log "
                      + "WHERE savings_transaction_id = ? "
                      + "   OR codigo_referencia = ? "
                      + "   OR id_relacion_cliente = ? "
                      + "ORDER BY id DESC LIMIT 1";
      List<Map<String, Object>> rows =
              this.jdbcTemplate.queryForList(sql, satId, sinpeRef, receipt);
      if (rows.isEmpty()) {
        return out;
      }
      String body =
              firstNonBlank(
                      stringVal(rows.get(0).get("response_xml")),
                      stringVal(rows.get(0).get("request_xml")));
      if (body.isEmpty()) {
        return out;
      }
      out.put(
              "name",
              extractTag(body, "OriginCustomerName", "originCustomerName", "holder", "nombre"));
      out.put(
              "iban",
              extractTag(body, "OriginCustomerIBAN", "originCustomerIBAN", "iban", "cuenta"));
      out.put(
              "id",
              extractTag(
                      body,
                      "OriginCustomerId",
                      "originCustomerId",
                      "holderId",
                      "identificacion"));
      out.put("email", extractTag(body, "email", "correo"));
    } catch (Exception e) {
      log.warn("loadExternalPersonFromAudit failed satId={}: {}", satId, e.getMessage());
    }
    return out;
  }

  private static String stringVal(Object o) {
    return o == null ? "" : o.toString();
  }

  private static String extractTag(String body, String... keys) {
    if (body == null) {
      return "";
    }
    for (String key : keys) {
      String xml = tagValue(body, key);
      if (!xml.isEmpty()) {
        return xml;
      }
      String json = jsonValue(body, key);
      if (!json.isEmpty()) {
        return json;
      }
    }
    return "";
  }

  private static String tagValue(String xml, String tag) {
    String open = "<" + tag + ">";
    String close = "</" + tag + ">";
    int i = xml.indexOf(open);
    if (i < 0) {
      int j = xml.indexOf("<" + tag);
      if (j < 0) {
        return "";
      }
      int gt = xml.indexOf('>', j);
      if (gt < 0) {
        return "";
      }
      int k = xml.indexOf(close, gt);
      if (k < 0) {
        return "";
      }
      return xml.substring(gt + 1, k).trim();
    }
    int j = xml.indexOf(close, i);
    if (j < 0) {
      return "";
    }
    return xml.substring(i + open.length(), j).trim();
  }

  private static String jsonValue(String body, String key) {
    String pattern = "\"" + key + "\"";
    int i = body.indexOf(pattern);
    if (i < 0) {
      return "";
    }
    int colon = body.indexOf(':', i + pattern.length());
    if (colon < 0) {
      return "";
    }
    int q1 = body.indexOf('"', colon + 1);
    if (q1 < 0) {
      return "";
    }
    int q2 = body.indexOf('"', q1 + 1);
    if (q2 < 0) {
      return "";
    }
    return body.substring(q1 + 1, q2).trim();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String v : values) {
      if (v != null && !v.trim().isEmpty()) {
        return v.trim();
      }
    }
    return "";
  }

  private static String padBankCode(String code) {
    if (code == null || code.isBlank()) {
      return "";
    }
    String c = code.trim();
    return c.length() == 3 ? "0" + c : c;
  }

  private static String stripLeadingZero(String code) {
    if (code == null || code.isBlank()) {
      return "";
    }
    String c = code.trim();
    if (c.length() == 4 && c.startsWith("0")) {
      return c.substring(1);
    }
    return c;
  }

  private static String inferIdType(String id) {
    if (id == null || id.isBlank()) {
      return "0";
    }
    String clean = id.replace("-", "").trim();
    // Costa Rica jurídica often 10 digits starting with 3; keep natural as default for cédulas with dashes
    if (clean.length() == 10 && clean.startsWith("3") && !id.contains("-")) {
      return "2";
    }
    return "0";
  }

  private static String idTypeDescription(String idType) {
    return "2".equals(idType) ? "Persona Jurídica Nacional" : "Persona Natural";
  }

  private String getExternalReceiptNumber(Long accountId, String txnId) {
    String sql =
            "SELECT COALESCE(NULLIF(pd.routing_code, ''), NULLIF(pd.receipt_number, '')) AS external_ref "
                    + "FROM m_savings_account_transaction sat "
                    + "INNER JOIN m_payment_detail pd ON sat.payment_detail_id = pd.id "
                    + "WHERE sat.savings_account_id = ? "
                    + "  AND ( "
                    + "        CAST(sat.id AS VARCHAR) = ? "
                    + "     OR sat.ref_no = ? "
                    + "     OR CAST(pd.id AS VARCHAR) = ? "
                    + "     OR pd.receipt_number = ? "
                    + "     OR pd.routing_code = ? "
                    + "  ) "
                    + "ORDER BY sat.id DESC "
                    + "LIMIT 1";
    try {
      return this.jdbcTemplate.queryForObject(
              sql, String.class, accountId, txnId, txnId, txnId, txnId, txnId);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      log.warn("No payment detail found for accountId: {} and txnId: {}", accountId, txnId);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Homologation
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private Map<String, Object> homologateResponseData(
          Map<String, Object> rawData,
          BigDecimal fallbackAmount,
          String fallbackCurrency,
          String transferType) {

    if (rawData == null) {
      rawData = new HashMap<>();
    }

    Map<String, Object> data = new HashMap<>(rawData);
    Map<String, Object> resultObj =
            data.get("result") instanceof Map
                    ? (Map<String, Object>) data.get("result")
                    : new HashMap<>();

    String operationId =
            data.containsKey("operationId")
                    && data.get("operationId") != null
                    && !data.get("operationId").toString().isEmpty()
                    ? data.get("operationId").toString()
                    : (resultObj.get("cbTrxNumber") != null
                            ? resultObj.get("cbTrxNumber").toString()
                            : "");

    String internalRef =
            data.get("internalRefNumber") != null
                    && !data.get("internalRefNumber").toString().isEmpty()
                    ? data.get("internalRefNumber").toString()
                    : (data.get("channelRefNumber") != null
                            ? data.get("channelRefNumber").toString()
                            : "");

    String channelRef =
            data.get("channelRefNumber") != null
                    && !data.get("channelRefNumber").toString().isEmpty()
                    ? data.get("channelRefNumber").toString()
                    : internalRef;

    String sinpeRef =
            resultObj.containsKey("sinpeRefNumber") && resultObj.get("sinpeRefNumber") != null
                    ? resultObj.get("sinpeRefNumber").toString()
                    : (data.get("sinpeRefNumber") != null
                            ? data.get("sinpeRefNumber").toString()
                            : "");

    Object rawDebited =
            resultObj.get("debitedAmount") != null
                    ? resultObj.get("debitedAmount")
                    : (data.get("debitedAmount") != null
                            ? data.get("debitedAmount")
                            : data.get("amount"));
    BigDecimal debitedAmount =
            rawDebited != null ? new BigDecimal(rawDebited.toString()) : fallbackAmount;

    String debitCurrencyCode =
            data.get("debitCurrencyCode") != null
                    ? data.get("debitCurrencyCode").toString()
                    : (data.get("currency") != null
                            ? data.get("currency").toString()
                            : fallbackCurrency);

    Object rawCommission =
            resultObj.get("commissionAmount") != null
                    ? resultObj.get("commissionAmount")
                    : data.get("commissionAmount");
    BigDecimal commissionAmount =
            rawCommission != null ? new BigDecimal(rawCommission.toString()) : BigDecimal.ZERO;

    String commissionCurrency =
            data.get("commissionCurrency") != null
                    ? data.get("commissionCurrency").toString()
                    : debitCurrencyCode;

    Object rawExchangeRate =
            resultObj.get("exchangeRate") != null
                    ? resultObj.get("exchangeRate")
                    : data.get("exchangeRate");
    BigDecimal exchangeRate =
            rawExchangeRate != null ? new BigDecimal(rawExchangeRate.toString()) : BigDecimal.ZERO;

    String registrationDate =
            resultObj.containsKey("registrationDate") && resultObj.get("registrationDate") != null
                    ? resultObj.get("registrationDate").toString()
                    : (data.get("registrationDate") != null
                            ? data.get("registrationDate").toString()
                            : "");

    String processingDate =
            resultObj.containsKey("processingDate") && resultObj.get("processingDate") != null
                    ? resultObj.get("processingDate").toString()
                    : (data.get("processingDate") != null
                            ? data.get("processingDate").toString()
                            : "");

    Integer stateCode = 32;
    Object rawState =
            resultObj.containsKey("stateCode") ? resultObj.get("stateCode") : data.get("stateCode");
    if (rawState != null) {
      try {
        stateCode = Double.valueOf(rawState.toString()).intValue();
      } catch (Exception ignored) {
      }
    }

    String stateDescription =
            resultObj.containsKey("stateDescription") && resultObj.get("stateDescription") != null
                    ? resultObj.get("stateDescription").toString()
                    : (stateCode == 32
                            ? "Completed"
                            : (stateCode == 128 ? "Rejected" : "Pending"));

    Integer rejectCode = 0;
    Object rawReject =
            resultObj.containsKey("rejectCode") ? resultObj.get("rejectCode") : data.get("rejectCode");
    if (rawReject != null) {
      try {
        rejectCode = Double.valueOf(rawReject.toString()).intValue();
      } catch (Exception ignored) {
      }
    }

    String rejectDescription =
            resultObj.containsKey("rejectDescription") && resultObj.get("rejectDescription") != null
                    ? resultObj.get("rejectDescription").toString()
                    : (data.get("rejectDescription") != null
                            ? data.get("rejectDescription").toString()
                            : "");

    Map<String, Object> customData = new HashMap<>();
    if (data.get("customData") instanceof Map) {
      customData = new HashMap<>((Map<String, Object>) data.get("customData"));
    }

    if (!customData.containsKey("fromAccountIdentifier")) {
      if (data.get("originCustomer") instanceof Map) {
        Map<String, Object> origin = (Map<String, Object>) data.get("originCustomer");
        customData.put("fromAccountIdentifier", origin.getOrDefault("iban", ""));
      }
    }
    if (!customData.containsKey("toAccountIdentifier")) {
      if (data.get("destinationCustomer") instanceof Map) {
        Map<String, Object> dest = (Map<String, Object>) data.get("destinationCustomer");
        customData.put("toAccountIdentifier", dest.getOrDefault("iban", ""));
      } else if (data.containsKey("destinationPhone")) {
        customData.put("toAccountIdentifier", data.get("destinationPhone"));
      } else if (resultObj.containsKey("destinationPhone")) {
        customData.put("toAccountIdentifier", resultObj.get("destinationPhone"));
      }
    }
    if (!customData.containsKey("transferDescription") && data.containsKey("description")) {
      customData.put("transferDescription", data.get("description"));
    }

    Map<String, Object> originCustomer;
    if (customData.get("originCustomer") instanceof Map) {
      originCustomer = new HashMap<>((Map<String, Object>) customData.get("originCustomer"));
    } else if (data.get("originCustomer") instanceof Map) {
      originCustomer = new HashMap<>((Map<String, Object>) data.get("originCustomer"));
    } else {
      String fromAccount = customData.getOrDefault("fromAccountIdentifier", "").toString();
      originCustomer = getOriginCustomerInfoByAccount(fromAccount);
    }
    customData.put("originCustomer", originCustomer);

    Map<String, Object> destCustomer;
    if (customData.get("destinationCustomer") instanceof Map) {
      destCustomer = new HashMap<>((Map<String, Object>) customData.get("destinationCustomer"));
    } else if (data.get("destinationCustomer") instanceof Map) {
      destCustomer = new HashMap<>((Map<String, Object>) data.get("destinationCustomer"));
    } else {
      destCustomer = new HashMap<>();
      destCustomer.put("name", "");
      destCustomer.put("id", "");
      destCustomer.put("email", "");
      destCustomer.put("iban", customData.getOrDefault("toAccountIdentifier", ""));
      destCustomer.put("idType", "0");
      destCustomer.put("idTypeDescription", "Persona Física Nacional (Cédula)");
    }

    boolean isInternalFallback = "INTERNAL_FALLBACK".equals(customData.get("source"));
    if (!isInternalFallback) {
      String target = "";
      if (customData.get("toAccountIdentifier") != null
              && !customData.get("toAccountIdentifier").toString().isBlank()) {
        target = customData.get("toAccountIdentifier").toString().trim();
      } else if (destCustomer.get("iban") != null
              && !destCustomer.get("iban").toString().isBlank()) {
        target = destCustomer.get("iban").toString().trim();
      }

      boolean needsResolution =
              isStringBlank(destCustomer.get("entityCode"))
                      || isStringBlank(destCustomer.get("entityName"))
                      || isStringBlank(destCustomer.get("name"))
                      || isStringBlank(destCustomer.get("id"));

      if (needsResolution && !target.isBlank()) {
        Map<String, String> resolvedDetails = resolveDestinationDetails(target);
        if (resolvedDetails.containsKey("entityCode")
                && !isStringBlank(resolvedDetails.get("entityCode"))) {
          destCustomer.put("entityCode", resolvedDetails.get("entityCode"));
        }
        if (resolvedDetails.containsKey("entityName")
                && !isStringBlank(resolvedDetails.get("entityName"))) {
          destCustomer.put("entityName", resolvedDetails.get("entityName"));
        }
        if (isStringBlank(destCustomer.get("name")) && resolvedDetails.containsKey("holder")) {
          destCustomer.put("name", resolvedDetails.get("holder"));
        }
        if (isStringBlank(destCustomer.get("id")) && resolvedDetails.containsKey("holderId")) {
          destCustomer.put("id", resolvedDetails.get("holderId"));
        }
      }
    }

    customData.put("destinationCustomer", destCustomer);

    data.clear();
    if (("PIN".equalsIgnoreCase(transferType)
            || "SINPE".equalsIgnoreCase(transferType)
            || "SINPE_MOVIL".equalsIgnoreCase(transferType))
            && sinpeRef != null) {
      data.put("internalRefNumber", sinpeRef);
    } else {
      data.put("internalRefNumber", internalRef != null ? internalRef : "");
    }
    data.put("operationId", operationId);
    data.put("channelRefNumber", channelRef);
    data.put("sinpeRefNumber", sinpeRef);
    data.put("debitedAmount", debitedAmount);
    data.put("debitCurrencyCode", debitCurrencyCode);
    data.put("commissionAmount", commissionAmount);
    data.put("commissionCurrency", commissionCurrency);
    data.put("exchangeRate", exchangeRate);
    data.put("registrationDate", registrationDate);
    data.put("processingDate", processingDate);
    data.put("stateCode", stateCode);
    data.put("stateDescription", stateDescription);
    data.put("rejectCode", rejectCode);
    data.put("rejectDescription", rejectDescription);
    data.put("successful", stateCode == 32);
    data.put("customData", customData);
    return data;
  }

  private static final class SelfAccountTemplateMapper
          implements RowMapper<SelfAccountTemplateData> {
    @Override
    public SelfAccountTemplateData mapRow(
            final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {
      final Long accountId = rs.getLong("accountId");
      final String accountNo = rs.getString("accountNo");
      final Integer accountType = rs.getInt("accountType");
      final Long clientId = rs.getLong("clientId");
      final String clientName = rs.getString("clientName");
      final Long officeId = rs.getLong("officeId");
      final String officeName = rs.getString("officeName");
      return new SelfAccountTemplateData(
              accountId, accountNo, accountType, clientId, clientName, officeId, officeName);
    }
  }

  private Map<String, Object> getDestinationCustomerInfoByAccount(String accountIdentifier) {
    Map<String, Object> destinationCustomer = new HashMap<>();
    if (accountIdentifier == null || accountIdentifier.isBlank()) {
      destinationCustomer.put("name", "");
      destinationCustomer.put("email", "");
      destinationCustomer.put("iban", "");
      destinationCustomer.put("id", "");
      destinationCustomer.put("idType", "0");
      destinationCustomer.put("idTypeDescription", "Persona Física Nacional (Cédula)");
      return destinationCustomer;
    }

    String sql =
            "SELECT "
                    + "c.display_name AS name, "
                    + "COALESCE(c.email_address, '') AS email, "
                    + "COALESCE(NULLIF(sa.external_id, ''), sa.account_no) AS iban, "
                    + "COALESCE(ci.document_key, '') AS id, "
                    + "COALESCE(CAST(cv.order_position AS VARCHAR), '0') AS idType, "
                    + "COALESCE(cv.code_value, 'Persona Física Nacional (Cédula)') AS idTypeDescription "
                    + "FROM m_savings_account sa "
                    + "INNER JOIN m_client c ON sa.client_id = c.id "
                    + "LEFT JOIN m_client_identifier ci ON c.id = ci.client_id "
                    + "LEFT JOIN m_code_value cv ON ci.document_type_id = cv.id "
                    + "WHERE (sa.external_id = ? OR sa.account_no = ? OR CAST(sa.id AS VARCHAR) = ?) "
                    + "LIMIT 1";
    try {
      Map<String, Object> result =
              this.jdbcTemplate.queryForMap(
                      sql, accountIdentifier, accountIdentifier, accountIdentifier);
      destinationCustomer.put("name", result.getOrDefault("name", ""));
      destinationCustomer.put("email", result.getOrDefault("email", ""));
      destinationCustomer.put("iban", result.getOrDefault("iban", accountIdentifier));
      destinationCustomer.put("id", result.getOrDefault("id", ""));
      destinationCustomer.put("idType", result.getOrDefault("idType", "0"));
      destinationCustomer.put(
              "idTypeDescription",
              result.getOrDefault("idTypeDescription", "Persona Física Nacional (Cédula)"));
    } catch (Exception e) {
      log.warn(
              "Could not find destination customer info for accountIdentifier: {}",
              accountIdentifier);
      destinationCustomer.put("name", "");
      destinationCustomer.put("email", "");
      destinationCustomer.put("iban", accountIdentifier);
      destinationCustomer.put("id", "");
      destinationCustomer.put("idType", "0");
      destinationCustomer.put("idTypeDescription", "Persona Física Nacional (Cédula)");
    }
    return destinationCustomer;
  }

  private Map<String, Object> getOriginCustomerInfoByAccount(String accountIdentifier) {
    Map<String, Object> originCustomer = new HashMap<>();
    if (accountIdentifier == null || accountIdentifier.isBlank()) {
      originCustomer.put("name", "");
      originCustomer.put("email", "");
      originCustomer.put("iban", "");
      originCustomer.put("id", "");
      originCustomer.put("idType", "");
      originCustomer.put("idTypeDescription", "");
      return originCustomer;
    }

    String sql =
            "SELECT "
                    + "c.display_name AS name, "
                    + "COALESCE(c.email_address, '') AS email, "
                    + "COALESCE(NULLIF(sa.external_id, ''), sa.account_no) AS iban, "
                    + "COALESCE(ci.document_key, '') AS id, "
                    + "COALESCE(CAST(cv.order_position AS VARCHAR), '') AS idType, "
                    + "COALESCE(cv.code_value, '') AS idTypeDescription "
                    + "FROM m_savings_account sa "
                    + "INNER JOIN m_client c ON sa.client_id = c.id "
                    + "LEFT JOIN m_client_identifier ci ON c.id = ci.client_id "
                    + "LEFT JOIN m_code_value cv ON ci.document_type_id = cv.id "
                    + "WHERE (sa.external_id = ? OR sa.account_no = ? OR CAST(sa.id AS VARCHAR) = ?) "
                    + "LIMIT 1";
    try {
      Map<String, Object> result =
              this.jdbcTemplate.queryForMap(
                      sql, accountIdentifier, accountIdentifier, accountIdentifier);
      originCustomer.put("name", result.getOrDefault("name", ""));
      originCustomer.put("email", result.getOrDefault("email", ""));
      originCustomer.put("iban", result.getOrDefault("iban", accountIdentifier));
      originCustomer.put("id", result.getOrDefault("id", ""));
      originCustomer.put("idType", result.getOrDefault("idType", ""));
      originCustomer.put("idTypeDescription", result.getOrDefault("idTypeDescription", ""));
    } catch (Exception e) {
      log.warn(
              "Could not find origin customer info for accountIdentifier: {}", accountIdentifier);
      originCustomer.put("name", "");
      originCustomer.put("email", "");
      originCustomer.put("iban", accountIdentifier);
      originCustomer.put("id", "");
      originCustomer.put("idType", "");
      originCustomer.put("idTypeDescription", "");
    }
    return originCustomer;
  }

  private PlatformApiDataValidationException accountAccessValidationError(Long accountId) {
    final List<ApiParameterError> errors = new ArrayList<>();
    errors.add(
            ApiParameterError.parameterError(
                    "error.msg.self.account.transfer.account.not.owned",
                    "The specified account does not belong to the authenticated client.",
                    "accountId",
                    accountId));
    return new PlatformApiDataValidationException(errors);
  }

  private PlatformApiDataValidationException unsupportedTransferTypeValidationError(
          String transferType) {
    final List<ApiParameterError> errors = new ArrayList<>();
    errors.add(
            ApiParameterError.parameterError(
                    "error.msg.self.account.transfer.transferType.unsupported",
                    "Unsupported transferType: " + transferType,
                    "transferType",
                    transferType));
    return new PlatformApiDataValidationException(errors);
  }

  private PlatformApiDataValidationException externalServiceValidationError(
          String transferType, String externalRef, Exception cause) {
    final List<ApiParameterError> errors = new ArrayList<>();
    errors.add(
            ApiParameterError.parameterError(
                    "error.msg.self.account.transfer.external.service.failed",
                    "Could not retrieve "
                            + transferType
                            + " transaction details for reference: "
                            + externalRef
                            + (cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""),
                    "txnId",
                    externalRef));
    return new PlatformApiDataValidationException(errors);
  }
}