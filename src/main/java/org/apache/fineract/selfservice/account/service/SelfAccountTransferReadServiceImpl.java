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
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAuditRepository;
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
  public Collection<SelfAccountTemplateData> retrieveSelfAccountTemplateData(AppSelfServiceUser user) {
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
    return this.jdbcTemplate.query(sql.toString(), mapper, new Object[] {user.getId(), user.getId()});
  }

  // =====================================================================
  //  MÉTODO PRINCIPAL: CONSULTA DETALLE DE TRANSACCIÓN
  // =====================================================================
  @Override
  @Transactional(readOnly = true)
  public Map<String, Object> retrieveTransactionDetails(Long accountId, String txnId, String transferType) {

    // 1. Validar pertenencia general de la cuenta al cliente autenticado
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SavingsAccount savingsAccount = this.savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId);
    if (!savingsAccount.getClient().getId().equals(client.getId())) {
      log.error("SECURITY ALERT: User {} attempted to access account {} not belonging to them.", user.getId(), accountId);
      throw new IllegalArgumentException("The specified account does not belong to the authenticated client.");
    }

    log.info("FETCH DETAILS: Querying details for account: {}, txnId: {}, transferType: {}", accountId, txnId, transferType);

    Map<String, Object> rawData;

    // 2. Delegar procesamiento al método correspondiente según el tipo
    if ("Apolo".equalsIgnoreCase(transferType) || "SAME_BANK".equalsIgnoreCase(transferType)) {
      rawData = processApoloTransfer(client.getId(), accountId, txnId);
    } else if ("PIN".equalsIgnoreCase(transferType) || "SINPE".equalsIgnoreCase(transferType) || "SINPE_MOVIL".equalsIgnoreCase(transferType)) {
      rawData = processExternalTransfer(accountId, txnId, transferType);
    } else {
      throw new IllegalArgumentException("Unsupported transferType: " + transferType);
    }

    // 3. Aplica homologación estándar
    BigDecimal fallbackAmount = rawData.containsKey("debitedAmount") && rawData.get("debitedAmount") != null
            ? new BigDecimal(rawData.get("debitedAmount").toString()) : BigDecimal.ZERO;
    String fallbackCurrency = rawData.getOrDefault("debitCurrencyCode", "CRC").toString();

    Map<String, Object> homologatedData = homologateResponseData(rawData, fallbackAmount, fallbackCurrency);

    Map<String, Object> response = new HashMap<>();
    response.put("transferType", transferType.toUpperCase());
    response.put("data", homologatedData);

    return response;
  }

  // =====================================================================
  //  MÉTODO 1: PROCESAR TRANSFERENCIAS INTERNAS (APOLO / SAME_BANK)
  // =====================================================================
  private Map<String, Object> processApoloTransfer(Long clientId, Long accountId, String txnId) {
    Optional<SelfServiceSameBankTransferAudit> auditOpt =
            this.sameBankTransferAuditRepository.findAuditDetail(clientId, accountId, txnId);

    if (auditOpt.isEmpty()) {
      throw new IllegalArgumentException("Internal transfer transaction not found for account " + accountId + " and txnId: " + txnId);
    }

    SelfServiceSameBankTransferAudit audit = auditOpt.get();
    Map<String, Object> rawData = new HashMap<>();

    rawData.put("operationId", audit.getOperationId());
    rawData.put("internalRefNumber", audit.getInternalRefNumber());
    rawData.put("channelRefNumber", audit.getInternalRefNumber());
    rawData.put("sinpeRefNumber", "");
    rawData.put("debitedAmount", audit.getTransferAmount());
    rawData.put("debitCurrencyCode", audit.getCurrencyCode());
    rawData.put("commissionAmount", audit.getFeeAmount() != null ? audit.getFeeAmount() : BigDecimal.ZERO);
    rawData.put("commissionCurrency", audit.getCurrencyCode());
    rawData.put("exchangeRate", 0);
    rawData.put("registrationDate", audit.getRegistrationDate() != null ? audit.getRegistrationDate().toString() : "");
    rawData.put("processingDate", audit.getProcessingDate() != null ? audit.getProcessingDate().toString() : "");
    rawData.put("stateDescription", audit.getStateDescription());
    rawData.put("successful", audit.isSuccessful());
    rawData.put("stateCode", audit.isSuccessful() ? 32 : 128);
    rawData.put("rejectDescription", audit.getRejectDescription() != null ? audit.getRejectDescription() : "");

    Map<String, Object> customData = new HashMap<>();
    customData.put("fromAccountIdentifier", audit.getFromAccountIdentifier());
    customData.put("toAccountIdentifier", audit.getToAccountIdentifier());
    customData.put("transferDescription", audit.getTransferDescription());
    customData.put("reference", audit.getReference());
    rawData.put("customData", customData);

    return rawData;
  }

  // =====================================================================
  //  MÉTODO 2: PROCESAR TRANSFERENCIAS EXTERNAS (PIN / SINPE)
  // =====================================================================
  private Map<String, Object> processExternalTransfer(Long accountId, String txnId, String transferType) {
    // A. Obtener el receipt_number desde m_payment_detail mediante validación de pertenencia
    String externalRef = getExternalReceiptNumber(accountId, txnId);
    if (externalRef == null || externalRef.isEmpty()) {
      throw new IllegalArgumentException("External transaction not found or does not belong to account: " + accountId);
    }

    Map<String, Object> rawData;

    // B. Consultar el servicio externo correspondiente pasándole la referencia externa
    try {
      if ("PIN".equalsIgnoreCase(transferType)) {
        log.info("Querying PIN external service with receiptRef: {}", externalRef);
        String pinResponseJson = this.pinExternalTransferService.getTransactionDetail(externalRef);
        rawData = this.gson.fromJson(pinResponseJson, Map.class);
      } else {
        log.info("Querying SINPE external service with receiptRef: {}", externalRef);
        Object sinpeResponse = this.sinpeExternalApiClient.getTransactionDetail(externalRef);
        String sinpeJson = sinpeResponse instanceof String ? (String) sinpeResponse : this.gson.toJson(sinpeResponse);
        rawData = this.gson.fromJson(sinpeJson, Map.class);
      }
    } catch (Exception e) {
      log.error("Error querying {} transaction detail for ref: {}", transferType, externalRef, e);
      throw new RuntimeException("Could not retrieve " + transferType + " transaction details.", e);
    }

    return rawData;
  }

  // Helper SQL para extraer receipt_number de m_payment_detail vinculando la transacción
  private String getExternalReceiptNumber(Long accountId, String txnId) {
    String sql = "SELECT pd.receipt_number " +
            "FROM m_savings_account_transaction sat " +
            "INNER JOIN m_payment_detail pd ON sat.payment_detail_id = pd.id " +
            "WHERE sat.savings_account_id = ? " +
            "AND (CAST(sat.id AS VARCHAR) = ? OR sat.ref_no = ?)";
    try {
      return this.jdbcTemplate.queryForObject(sql, String.class, accountId, txnId, txnId);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      log.warn("No payment detail found for accountId: {} and txnId: {}", accountId, txnId);
      return null;
    }
  }

  // =====================================================================
  //  HELPER HOMOLOGACIÓN DE RESPUESTA
  // =====================================================================
  private Map<String, Object> homologateResponseData(Map<String, Object> rawData, BigDecimal fallbackAmount, String fallbackCurrency) {
    if (rawData == null) rawData = new HashMap<>();
    Map<String, Object> data = new HashMap<>(rawData);

    String operationId = data.containsKey("operationId") && data.get("operationId") != null ? data.get("operationId").toString() : "";
    String internalRef = data.get("internalRefNumber") != null ? data.get("internalRefNumber").toString() : (data.get("channelRefNumber") != null ? data.get("channelRefNumber").toString() : "");
    String channelRef = data.get("channelRefNumber") != null ? data.get("channelRefNumber").toString() : (data.get("internalRefNumber") != null ? data.get("internalRefNumber").toString() : "");
    String sinpeRef = data.get("sinpeRefNumber") != null ? data.get("sinpeRefNumber").toString() : "";

    Object rawDebited = data.get("debitedAmount") != null ? data.get("debitedAmount") : data.get("amount");
    BigDecimal debitedAmount = rawDebited != null ? new BigDecimal(rawDebited.toString()) : fallbackAmount;
    String debitCurrencyCode = data.get("debitCurrencyCode") != null ? data.get("debitCurrencyCode").toString() : (data.get("currency") != null ? data.get("currency").toString() : fallbackCurrency);

    BigDecimal commissionAmount = data.get("commissionAmount") != null ? new BigDecimal(data.get("commissionAmount").toString()) : BigDecimal.ZERO;
    String commissionCurrency = data.get("commissionCurrency") != null ? data.get("commissionCurrency").toString() : debitCurrencyCode;
    BigDecimal exchangeRate = data.get("exchangeRate") != null ? new BigDecimal(data.get("exchangeRate").toString()) : BigDecimal.ZERO;

    String registrationDate = data.get("registrationDate") != null ? data.get("registrationDate").toString() : "";
    String processingDate = data.get("processingDate") != null ? data.get("processingDate").toString() : "";

    Integer stateCode = 32;
    Object rawState = data.containsKey("stateCode") ? data.get("stateCode") : data.get("state");
    if (rawState != null) {
      try { stateCode = Double.valueOf(rawState.toString()).intValue(); } catch (Exception ignored) {}
    }

    String stateDescription = stateCode == 32 ? "Completed" : (stateCode == 128 ? "Rejected" : "Pending");
    Integer rejectCode = data.get("rejectCode") != null ? Double.valueOf(data.get("rejectCode").toString()).intValue() : (stateCode == 128 ? 128 : 0);
    String rejectDescription = data.get("rejectDescription") != null ? data.get("rejectDescription").toString() : "";

    data.remove("amount");
    data.remove("currency");
    data.remove("state");

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
    data.put("customData", data.get("customData"));

    return data;
  }

  private static final class SelfAccountTemplateMapper implements RowMapper<SelfAccountTemplateData> {
    @Override
    public SelfAccountTemplateData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {
      final Long accountId = rs.getLong("accountId");
      final String accountNo = rs.getString("accountNo");
      final Integer accountType = rs.getInt("accountType");
      final Long clientId = rs.getLong("clientId");
      final String clientName = rs.getString("clientName");
      final Long officeId = rs.getLong("officeId");
      final String officeName = rs.getString("officeName");

      return new SelfAccountTemplateData(accountId, accountNo, accountType, clientId, clientName, officeId, officeName);
    }
  }
}