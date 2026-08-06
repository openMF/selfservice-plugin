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

  // =====================================================================
  // CONSULTA DETALLE DE TRANSACCIÓN
  // =====================================================================

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
        homologateResponseData(rawData, fallbackAmount, fallbackCurrency);

    Map<String, Object> response = new HashMap<>();
    response.put("transferType", transferType.toUpperCase());
    response.put("data", homologatedData);
    return response;
  }

  // =====================================================================
  // PROCESAR TRANSFERENCIAS INTERNAS (APOLO / SAME_BANK)
  // =====================================================================

  private Map<String, Object> processApoloTransfer(
      Long clientId, Long accountId, String txnId, String transferType) {

    Optional<SelfServiceSameBankTransferAudit> auditOpt =
        this.sameBankTransferAuditRepository.findAuditDetail(clientId, accountId, txnId);

    if (auditOpt.isEmpty()) {
      throw new SelfAccountTransferTransactionNotFoundException(accountId, txnId, transferType);
    }

    SelfServiceSameBankTransferAudit audit = auditOpt.get();
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

    Map<String, Object> customData = new HashMap<>();
    customData.put("fromAccountIdentifier", audit.getFromAccountIdentifier());
    customData.put("toAccountIdentifier", audit.getToAccountIdentifier());
    customData.put("transferDescription", audit.getTransferDescription());
    customData.put("reference", audit.getReference());
    customData.put("destinationCustomer", destinationCustomer);
    rawData.put("customData", customData);

    return rawData;
  }

  // =====================================================================
  // PROCESAR TRANSFERENCIAS EXTERNAS (PIN / SINPE)
  // =====================================================================

  private Map<String, Object> processExternalTransfer(
      Long accountId, String txnId, String transferType) {

    String externalRef = getExternalReceiptNumber(accountId, txnId);
    if (externalRef == null || externalRef.isEmpty()) {
      throw new SelfAccountTransferTransactionNotFoundException(accountId, txnId, transferType);
    }

    Map<String, Object> rawData;
    try {
      if ("PIN".equalsIgnoreCase(transferType)) {
        log.info("Querying PIN external service with receiptRef: {}", externalRef);
        String pinResponseJson = this.pinExternalTransferService.getTransactionDetail(externalRef);
        rawData = this.gson.fromJson(pinResponseJson, Map.class);
      } else {
        log.info("Querying SINPE external service with receiptRef: {}", externalRef);
        Object sinpeResponse = this.sinpeExternalApiClient.getTransactionDetail(externalRef);
        String sinpeJson =
            sinpeResponse instanceof String
                ? (String) sinpeResponse
                : this.gson.toJson(sinpeResponse);
        rawData = this.gson.fromJson(sinpeJson, Map.class);
      }
    } catch (SelfAccountTransferTransactionNotFoundException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error querying {} transaction detail for ref: {}", transferType, externalRef, e);
      throw externalServiceValidationError(transferType, externalRef, e);
    }

    return rawData;
  }

  private String getExternalReceiptNumber(Long accountId, String txnId) {
    String sql =
        "SELECT COALESCE(pd.routing_code, pd.receipt_number) AS external_ref "
            + "FROM m_savings_account_transaction sat "
            + "INNER JOIN m_payment_detail pd ON sat.payment_detail_id = pd.id "
            + "WHERE sat.savings_account_id = ? "
            + "AND (CAST(sat.id AS VARCHAR) = ? OR sat.ref_no = ?)";
    try {
      return this.jdbcTemplate.queryForObject(sql, String.class, accountId, txnId, txnId);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      log.warn("No payment detail found for accountId: {} and txnId: {}", accountId, txnId);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> homologateResponseData(
      Map<String, Object> rawData, BigDecimal fallbackAmount, String fallbackCurrency) {

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
      }
    }

    if (!customData.containsKey("transferDescription") && data.containsKey("description")) {
      customData.put("transferDescription", data.get("description"));
    }

    if (!customData.containsKey("destinationCustomer")
        || customData.get("destinationCustomer") == null) {
      Map<String, Object> destCustomer = new HashMap<>();
      if (data.get("destinationCustomer") instanceof Map) {
        destCustomer = new HashMap<>((Map<String, Object>) data.get("destinationCustomer"));
      } else {
        destCustomer.put("name", data.getOrDefault("destinationCustomerName", ""));
        destCustomer.put("id", data.getOrDefault("destinationCustomerId", ""));
        destCustomer.put("email", "");
        destCustomer.put("iban", customData.getOrDefault("toAccountIdentifier", ""));
        destCustomer.put("idType", "0");
        destCustomer.put("idTypeDescription", "Persona Física Nacional (Cédula)");
      }
      customData.put("destinationCustomer", destCustomer);
    }

    data.clear();
    data.put("operationId", operationId);
    data.put("internalRefNumber", internalRef);
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

  // =====================================================================
  // Platform validation helpers (HTTP 400 with developer/user messages)
  // =====================================================================

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