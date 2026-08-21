/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.RemittanceConfirmRequest;
import org.apache.fineract.selfservice.account.data.RemittancePayoutRequest;
import org.apache.fineract.selfservice.account.data.RemittancePrepareRequest;
import org.apache.fineract.selfservice.account.data.RemittanceRecipientRequest;
import org.apache.fineract.selfservice.account.data.RemittanceResponse;
import org.apache.fineract.selfservice.account.domain.SelfServiceRemittance;
import org.apache.fineract.selfservice.account.domain.SelfServiceRemittanceRepository;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RemittanceService {

  private final RemittanceExternalService externalService;
  private final SelfServiceRemittanceRepository remittanceRepository;
  private final PlatformSelfServiceSecurityContext context;
  private final Gson gson = new Gson();

  public String getAvailableVendors(String operationType) {
    return externalService.getAvailableVendors(operationType);
  }

  public String getProducts(String vendor, String acceptLanguage) {
    return externalService.getProducts(vendor, acceptLanguage);
  }

  public String getCountries(String vendor, String acceptLanguage) {
    return externalService.getCountries(vendor, acceptLanguage);
  }

  public String getDeliveryMethods(String vendor, String countryAbbrev, String productId,
      String acceptLanguage) {
    return externalService.getDeliveryMethods(vendor, countryAbbrev, productId, acceptLanguage);
  }

  public RemittanceResponse findRemittance(String vendor, String id) {
    String raw = externalService.findTransaction(vendor, id);
    JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

    RemittanceResponse.RemittanceResponseBuilder builder = RemittanceResponse.builder()
        .vendor(vendor)
        .pin(json.has("pin") ? json.get("pin").getAsString() : id)
        .status(json.has("status") ? json.get("status").getAsString() : "UNKNOWN")
        .senderName(json.has("senderName") ? json.get("senderName").getAsString() : null)
        .recipientName(json.has("recipientName") ? json.get("recipientName").getAsString() : null)
        .message("Transaction found");

    if (json.has("receivingAmount")) {
      builder.receivingAmount(json.get("receivingAmount").getAsBigDecimal());
    }
    if (json.has("receivingCurrency")) {
      builder.receivingCurrency(json.get("receivingCurrency").getAsString());
    }
    if (json.has("referenceNumber")) {
      builder.referenceNumber(json.get("referenceNumber").getAsString());
    }
    if (json.has("created")) {
      // leave as is; external may use different format
    }

    // Persist / update local audit if not exists
    remittanceRepository.findByPin(id).orElseGet(() -> {
      SelfServiceRemittance entity = new SelfServiceRemittance();
      AppSelfServiceUser user = context.authenticatedSelfServiceUser();
      Client client = user.getAppUserClientMappings().iterator().next().getClient();
      entity.setAppSelfServiceUserId(user.getId());
      entity.setClientId(client.getId());
      entity.setVendor(vendor);
      entity.setOperationType("PAYOUT");
      entity.setPin(id);
      entity.setStatus(builder.build().getStatus());
      entity.setSenderName(builder.build().getSenderName());
      entity.setRecipientName(builder.build().getRecipientName());
      entity.setReceivingAmount(builder.build().getReceivingAmount());
      entity.setReceivingCurrency(builder.build().getReceivingCurrency());
      entity.setCreatedOn(LocalDateTime.now());
      entity.setExternalResponse(raw);
      return remittanceRepository.save(entity);
    });

    return builder.build();
  }

  public String validateRecipient(String vendor, String transactionId,
      RemittanceRecipientRequest request) {
    return externalService.validateRecipient(vendor, transactionId, request);
  }

  @Transactional
  public RemittanceResponse assignPayout(String vendor, String transactionId,
      RemittancePayoutRequest request) {
    Map<String, Object> body = new HashMap<>();
    body.put("agent", request.getAgent() != null ? request.getAgent() : "SELF_SERVICE");
    Map<String, String> additional = new HashMap<>();
    additional.put("comment", request.getAdditionalInfoComment() != null
        ? request.getAdditionalInfoComment() : "Payout assignment from Self Service");
    body.put("additionalInfo", additional);

    String raw = externalService.assignPayout(vendor, transactionId, body);
    updateLocalStatus(transactionId, vendor, "READY_FOR_PAYOUT", raw);

    return RemittanceResponse.builder()
        .vendor(vendor)
        .pin(transactionId)
        .status("READY_FOR_PAYOUT")
        .message("Payout assigned successfully")
        .build();
  }

  @Transactional
  public RemittanceResponse confirmPayout(String vendor, String transactionId,
      RemittancePayoutRequest request) {
      
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();  
    Client client = user.getAppUserClientMappings().iterator().next().getClient();
    
    String clientId = request.getClientIdMifos() != null
        ? request.getClientIdMifos()
        : String.valueOf(client.getId());

    Map<String, Object> body = new HashMap<>();
    Map<String, String> additional = new HashMap<>();
    additional.put("comment", request.getAdditionalInfoComment() != null
        ? request.getAdditionalInfoComment() : "Payout confirmed from Self Service");
    body.put("additionalInfo", additional);

    String raw = externalService.confirmPayout(vendor, transactionId, clientId, body);
    updateLocalStatus(transactionId, vendor, "PAID", raw);

    return RemittanceResponse.builder()
        .vendor(vendor)
        .pin(transactionId)
        .status("PAID")
        .message("Payout confirmed successfully")
        .build();
  }

  @Transactional
  public RemittanceResponse unassignPayout(String vendor, String transactionId,
      RemittancePayoutRequest request) {
    Map<String, Object> body = new HashMap<>();
    body.put("reason", request.getReason() != null ? request.getReason() : "UNASSIGNED_PAYOUT");

    String raw = externalService.unassignPayout(vendor, transactionId, body);
    updateLocalStatus(transactionId, vendor, "PENDING", raw);

    return RemittanceResponse.builder()
        .vendor(vendor)
        .pin(transactionId)
        .status("PENDING")
        .message("Payout unassigned successfully")
        .build();
  }

  @Transactional
  public RemittanceResponse rejectPayout(String vendor, String transactionId,
      RemittancePayoutRequest request) {
    Map<String, Object> body = new HashMap<>();
    body.put("reasonCode", request.getReasonCode() != null
        ? request.getReasonCode() : "PAYERS-API.REJECT.UNKNOWN");
    body.put("reason", request.getReason() != null ? request.getReason() : "Rejected from Self Service");
    body.put("clientTimestampUtc", LocalDateTime.now().toString());

    String raw = externalService.rejectPayout(vendor, transactionId, body);
    updateLocalStatus(transactionId, vendor, "REJECTED", raw);

    return RemittanceResponse.builder()
        .vendor(vendor)
        .pin(transactionId)
        .status("REJECTED")
        .message("Payout rejected")
        .build();
  }

  /**
   * Prepare a quote / fee calculation for a SEND remittance (mirrors PaymentLink prepare).
   */
  public RemittanceResponse prepareRemittance(RemittancePrepareRequest request) {
    // For now return a local quote structure; full quote may call external if MS exposes it
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    SelfServiceRemittance entity = new SelfServiceRemittance();
    entity.setAppSelfServiceUserId(user.getId());
    entity.setClientId(client.getId());
    entity.setSavingsAccountId(request.getSavingsAccountId());
    entity.setVendor(request.getVendor() != null ? request.getVendor() : "RIA");
    entity.setOperationType(request.getOperationType() != null ? request.getOperationType() : "SEND");
    entity.setStatus("PREPARED");
    entity.setSendingAmount(request.getAmount());
    entity.setSendingCurrency(request.getCurrency());
    entity.setCountryFrom(request.getCountryFrom());
    entity.setCountryTo(request.getCountryTo());
    entity.setDeliveryMethod(request.getDeliveryMethod());
    entity.setProductId(request.getProductId());
    entity.setSenderName(
        (request.getSenderFirstName() != null ? request.getSenderFirstName() : "") + " "
            + (request.getSenderLastName() != null ? request.getSenderLastName() : ""));
    entity.setRecipientName(
        (request.getRecipientFirstName() != null ? request.getRecipientFirstName() : "") + " "
            + (request.getRecipientLastName() != null ? request.getRecipientLastName() : ""));
    entity.setCreatedOn(LocalDateTime.now());
    entity = remittanceRepository.save(entity);

    return RemittanceResponse.builder()
        .id(entity.getId())
        .vendor(entity.getVendor())
        .operationType(entity.getOperationType())
        .status("PREPARED")
        .sendingAmount(entity.getSendingAmount())
        .sendingCurrency(entity.getSendingCurrency())
        .feeAmount(BigDecimal.ZERO) // placeholder; real fee from external quote if available
        .feeCurrency(entity.getSendingCurrency())
        .senderName(entity.getSenderName())
        .recipientName(entity.getRecipientName())
        .countryFrom(entity.getCountryFrom())
        .countryTo(entity.getCountryTo())
        .deliveryMethod(entity.getDeliveryMethod())
        .createdOn(entity.getCreatedOn())
        .message("Remittance prepared. Proceed to confirm.")
        .build();
  }

  @Transactional
  public RemittanceResponse confirmRemittance(RemittanceConfirmRequest request) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    // Build payload for external create
    Map<String, Object> payload = buildCreatePayload(request);
    String raw = externalService.createRemittance(request.getVendor(), payload);

    JsonObject json = null;
    try {
      json = JsonParser.parseString(raw).getAsJsonObject();
    } catch (Exception e) {
      log.warn("Could not parse create response as JSON: {}", raw);
    }

    SelfServiceRemittance entity = new SelfServiceRemittance();
    entity.setAppSelfServiceUserId(user.getId());
    entity.setClientId(client.getId());
    entity.setSavingsAccountId(request.getSavingsAccountId());
    entity.setVendor(request.getVendor() != null ? request.getVendor() : "RIA");
    entity.setOperationType(request.getOperationType() != null ? request.getOperationType() : "SEND");
    entity.setStatus("POSTED");
    entity.setSendingAmount(request.getAmount());
    entity.setSendingCurrency(request.getCurrency());
    entity.setCountryFrom(request.getCountryFrom());
    entity.setCountryTo(request.getCountryTo());
    entity.setDeliveryMethod(request.getDeliveryMethod());
    entity.setProductId(request.getProductId());
    entity.setSenderName(
        (request.getSenderFirstName() != null ? request.getSenderFirstName() : "") + " "
            + (request.getSenderLastName() != null ? request.getSenderLastName() : ""));
    entity.setRecipientName(
        (request.getRecipientFirstName() != null ? request.getRecipientFirstName() : "") + " "
            + (request.getRecipientLastName() != null ? request.getRecipientLastName() : ""));
    entity.setCreatedOn(LocalDateTime.now());
    entity.setExternalResponse(raw);

    if (json != null) {
      if (json.has("pin")) {
        entity.setPin(json.get("pin").getAsString());
      }
      if (json.has("OrderNo") || json.has("orderNo") || json.has("referenceNumber")) {
        String ref = json.has("referenceNumber") ? json.get("referenceNumber").getAsString()
            : (json.has("OrderNo") ? json.get("OrderNo").getAsString() : json.get("orderNo").getAsString());
        entity.setReferenceNumber(ref);
      }
      if (json.has("status") || json.has("OrderStatus")) {
        entity.setStatus(json.has("status") ? json.get("status").getAsString()
            : json.get("OrderStatus").getAsString());
      }
    }

    entity = remittanceRepository.save(entity);

    return RemittanceResponse.builder()
        .id(entity.getId())
        .vendor(entity.getVendor())
        .operationType(entity.getOperationType())
        .externalId(entity.getExternalId())
        .pin(entity.getPin())
        .referenceNumber(entity.getReferenceNumber())
        .status(entity.getStatus())
        .sendingAmount(entity.getSendingAmount())
        .sendingCurrency(entity.getSendingCurrency())
        .senderName(entity.getSenderName())
        .recipientName(entity.getRecipientName())
        .countryFrom(entity.getCountryFrom())
        .countryTo(entity.getCountryTo())
        .deliveryMethod(entity.getDeliveryMethod())
        .createdOn(entity.getCreatedOn())
        .message("Remittance created successfully")
        .build();
  }

  private void updateLocalStatus(String pin, String vendor, String status, String rawResponse) {
    remittanceRepository.findByPinAndVendor(pin, vendor).ifPresent(entity -> {
      entity.setStatus(status);
      entity.setUpdatedOn(LocalDateTime.now());
      entity.setExternalResponse(rawResponse);
      remittanceRepository.save(entity);
    });
  }

  private Map<String, Object> buildCreatePayload(RemittanceConfirmRequest request) {
    // Simplified payload; real MS may expect nested Ria/Transaction/Quotation structure
    Map<String, Object> payload = new HashMap<>();
    payload.put("vendor", request.getVendor());
    payload.put("operationType", request.getOperationType());
    payload.put("productId", request.getProductId());
    payload.put("countryFrom", request.getCountryFrom());
    payload.put("countryTo", request.getCountryTo());
    payload.put("deliveryMethod", request.getDeliveryMethod());
    payload.put("amount", request.getAmount());
    payload.put("currency", request.getCurrency());
    payload.put("amountType", request.getAmountType());
    payload.put("transferReason", request.getTransferReason());

    Map<String, Object> sender = new HashMap<>();
    sender.put("firstName", request.getSenderFirstName());
    sender.put("lastName", request.getSenderLastName());
    sender.put("middleName", request.getSenderMiddleName());
    sender.put("dateOfBirth", request.getSenderDateOfBirth());
    sender.put("nationality", request.getSenderNationality());
    sender.put("documentType", request.getSenderDocumentType());
    sender.put("documentNumber", request.getSenderDocumentNumber());
    sender.put("address", request.getSenderAddress());
    sender.put("city", request.getSenderCity());
    sender.put("state", request.getSenderState());
    sender.put("zipCode", request.getSenderZipCode());
    sender.put("country", request.getSenderCountry());
    sender.put("phone", request.getSenderPhone());
    sender.put("occupation", request.getSenderOccupation());
    payload.put("sender", sender);

    Map<String, Object> recipient = new HashMap<>();
    recipient.put("firstName", request.getRecipientFirstName());
    recipient.put("lastName", request.getRecipientLastName());
    recipient.put("middleName", request.getRecipientMiddleName());
    recipient.put("motherMaidenName", request.getRecipientMotherMaidenName());
    recipient.put("dateOfBirth", request.getRecipientDateOfBirth());
    recipient.put("nationality", request.getRecipientNationality());
    recipient.put("city", request.getRecipientCity());
    recipient.put("state", request.getRecipientState());
    recipient.put("country", request.getRecipientCountry());
    recipient.put("address", request.getRecipientAddress());
    recipient.put("zipCode", request.getRecipientZipCode());
    recipient.put("phone", request.getRecipientPhone());
    recipient.put("email", request.getRecipientEmail());
    recipient.put("documentType", request.getRecipientDocumentType());
    recipient.put("documentNumber", request.getRecipientDocumentNumber());
    recipient.put("bankAccountNo", request.getBankAccountNo());
    recipient.put("bankRoutingCode", request.getBankRoutingCode());
    payload.put("recipient", recipient);

    payload.put("bankId", request.getBankId());
    payload.put("payoutPartnerId", request.getPayoutPartnerId());
    payload.put("payoutLocationId", request.getPayoutLocationId());
    payload.put("additionalInfo", request.getAdditionalInfo());

    return payload;
  }
}
