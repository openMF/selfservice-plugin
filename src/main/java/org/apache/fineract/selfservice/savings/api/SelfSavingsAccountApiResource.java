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
package org.apache.fineract.selfservice.savings.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.api.SavingsAccountChargesApiResource;
import org.apache.fineract.portfolio.savings.api.SavingsAccountTransactionsApiResource;
import org.apache.fineract.portfolio.savings.api.SavingsAccountsApiResource;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.savings.data.SelfSavingsAccountConstants;
import org.apache.fineract.selfservice.savings.data.SelfSavingsDataValidator;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Path("/v1/self/savingsaccounts")
@Component
@Tag(name = "Self Savings Account", description = "")
@RequiredArgsConstructor
@Slf4j
public class SelfSavingsAccountApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SavingsAccountsApiResource savingsAccountsApiResource;
  private final SavingsAccountChargesApiResource savingsAccountChargesApiResource;
  private final SavingsAccountTransactionsApiResource savingsAccountTransactionsApiResource;
  private final SelfSavingsDataValidator dataValidator;
  private final SelfServiceOwnershipGuard selfServiceOwnershipGuard;

  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;

  private final NotificationDeliveryModeUtil notificationDeliveryModeUtil;

  @GET
  @Path("{accountId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a savings account",
      description =
          "Retrieves a savings account\n\n"
              + "Example Requests:\n\n"
              + "self/savingsaccounts/1\n\n"
              + "self/savingsaccounts/1?associations=transactions\n\n"
              + "self/savingsaccounts/1?associations=transactions&month=5&year=2026\n\n"
              + "self/savingsaccounts/1?associations=transactions&lastTransactions=10\n\n"
              + "self/savingsaccounts/1?associations=transactions&month=5&year=2026&lastTransactions=5\n\n"
              + "self/savingsaccounts/1?associations=transactions&showReversed=true")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfSavingsAccountApiResourceSwagger.GetSelfSavingsAccountsResponse
                                .class)))
  })
  public SavingsAccountData retrieveSavings(
      @PathParam("accountId") @Parameter(description = "accountId") final Long accountId,
      @DefaultValue("all") @QueryParam("chargeStatus") @Parameter(description = "chargeStatus")
          final String chargeStatus,
      @QueryParam("month") @Parameter(description = "Filter transactions by month (1-12)")
          final Integer month,
      @QueryParam("year") @Parameter(description = "Filter transactions by year")
          final Integer year,
      @QueryParam("lastTransactions")
          @Parameter(description = "Return only the last N transactions (most recent)")
          final Integer lastTransactions,
      @DefaultValue("false")
          @QueryParam("showReversed")
          @Parameter(
              description =
                  "When true, include reversed transactions in the 'transactions' collection. "
                      + "Default is false (reversed transactions are hidden).")
          final boolean showReversed,
      @Context final UriInfo uriInfo) {

    this.dataValidator.validateRetrieveSavings(uriInfo);
    validateAppSelfServiceUserSavingsAccountMapping(accountId);

    final boolean staffInSelectedOfficeOnly = false;
    String dateRange = null;
    if (month != null && year != null) {
      if (month < 1 || month > 12) {
        throw new IllegalArgumentException("Month must be between 1 and 12");
      }
      String fromDate = String.format("%d-%02d-01", year, month);
      String toDate = String.format("%d-%02d-%02d", year, month, getLastDayOfMonth(year, month));
      dateRange = fromDate + "," + toDate;
    }

    SavingsAccountData savingsAccountData =
        this.savingsAccountsApiResource.retrieveOne(
            accountId, staffInSelectedOfficeOnly, chargeStatus, dateRange, uriInfo);

    Collection<SavingsAccountTransactionData> transactions = savingsAccountData.getTransactions();

    if (!CollectionUtils.isEmpty(transactions)) {
      List<SavingsAccountTransactionData> filtered = new ArrayList<>(transactions);

      // Keep money-movement (payment details) AND fee/charge AND hold/release rows.
      // Fee postings usually have no paymentDetailData; core Fineract still returns them.
      filtered =
          filtered.stream()
              .filter(Objects::nonNull)
              .filter(
                  t ->
                      t.getPaymentDetailData() != null
                          || t.getTransfer() != null // account-transfer deposit/withdrawal (e.g. commission credit)
                          || isFeeOrChargeTransaction(t)
                          || isHoldOrReleaseTransaction(t))
              .collect(Collectors.toList());

      // Hide reversed transactions by default (only affects the "transactions" collection)
      if (!showReversed) {
        filtered =
            filtered.stream()
                .filter(Objects::nonNull)
                .filter(t -> !t.isReversed())
                .collect(Collectors.toList());
      }

      // Optional month/year filter
      if (month != null && year != null) {
        filtered =
            filtered.stream()
                .filter(Objects::nonNull)
                .filter(
                    t -> {
                      LocalDate txDate = t.getTransactionDate();
                      if (txDate == null) {
                        txDate = t.getDate();
                      }
                      return txDate != null
                          && txDate.getYear() == year
                          && txDate.getMonthValue() == month;
                    })
                .collect(Collectors.toList());
      }

      // Optional "last N" limit (most recent first)
      if (lastTransactions != null && lastTransactions > 0) {
        filtered.sort(
            (t1, t2) -> {
              LocalDate d1 =
                  t1.getTransactionDate() != null ? t1.getTransactionDate() : t1.getDate();
              LocalDate d2 =
                  t2.getTransactionDate() != null ? t2.getTransactionDate() : t2.getDate();
              if (d1 == null && d2 == null) {
                return 0;
              }
              if (d1 == null) {
                return 1;
              }
              if (d2 == null) {
                return -1;
              }
              return d2.compareTo(d1);
            });

        if (filtered.size() > lastTransactions) {
          filtered = filtered.subList(0, lastTransactions);
        }
      }

      try {
        Field transactionsField = SavingsAccountData.class.getDeclaredField("transactions");
        transactionsField.setAccessible(true);
        transactionsField.set(savingsAccountData, filtered);
      } catch (Exception e) {
        log.warn("Warning: Could not set filtered transactions - {}", e.getMessage());
      }
    }

    // Note: savingsAccountTransactionsWithPivotConfig is intentionally left untouched
    return savingsAccountData;
  }

  /**
   * Fee / charge postings usually have no paymentDetailData; core Fineract still returns them.
   * Covers PAY_CHARGE, WITHDRAWAL_FEE, ANNUAL_FEE, and related feeDeduction types.
   */
  private static boolean isFeeOrChargeTransaction(SavingsAccountTransactionData t) {
    if (t == null || t.getTransactionType() == null) {
      return false;
    }
    var type = t.getTransactionType();
    try {
      if (Boolean.TRUE.equals(type.isFeeDeduction())
          || Boolean.TRUE.equals(type.isPayCharge())
          || Boolean.TRUE.equals(type.isWithdrawalFee())
          || Boolean.TRUE.equals(type.isAnnualFee())
          || Boolean.TRUE.equals(type.isOverdraftFee())) {
        return true;
      }
    } catch (Exception ignored) {
      // fall through to id/code checks for older DTOs
    }
    Integer id = type.getId().intValue();
    // Typical SavingsAccountTransactionType ids: PAY_CHARGE=3, WITHDRAWAL_FEE=4, ANNUAL_FEE=5, ...
    if (id != null && (id == 3 || id == 4 || id == 5 || id == 6 || id == 7 || id == 16)) {
      return true;
    }
    String code = type.getCode();
    if (code == null) {
      return false;
    }
    String c = code.toLowerCase();
    return c.contains("fee")
        || c.contains("charge")
        || c.contains("paycharge")
        || c.contains("fee_deduction")
        || c.contains("feededuction");
  }

  /** Amount on hold / release (ids 20 / 21) also lack paymentDetailData. */
  private static boolean isHoldOrReleaseTransaction(SavingsAccountTransactionData t) {
    if (t == null || t.getTransactionType() == null) {
      return false;
    }
    var type = t.getTransactionType();
    try {
      if (Boolean.TRUE.equals(type.isAmountHold())
          || Boolean.TRUE.equals(type.isAmountRelease())) {
        return true;
      }
    } catch (Exception ignored) {
      // ignore
    }
    Integer id = type.getId().intValue();
    if (id != null && (id == 20 || id == 21)) {
      return true;
    }
    String code = type.getCode();
    if (code == null) {
      return false;
    }
    String c = code.toLowerCase();
    return c.contains("hold") || c.contains("release");
  }

  private int getLastDayOfMonth(int year, int month) {
    java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
    return yearMonth.lengthOfMonth();
  }

  @GET
  @Path("{accountId}/transactions/{transactionId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Savings Account Transaction",
      description =
          "Retrieves Savings Account Transaction\n\n"
              + "Example Requests:\n\n"
              + "self/savingsaccounts/1/transactions/1")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfSavingsAccountApiResourceSwagger
                                .GetSelfSavingsAccountsAccountIdTransactionsTransactionIdResponse
                                .class)))
  })
  public String retrieveSavingsTransaction(
      @PathParam("accountId") @Parameter(description = "accountId") final Long accountId,
      @PathParam("transactionId") @Parameter(description = "transactionId")
          final Long transactionId,
      @Context final UriInfo uriInfo) {
    this.dataValidator.validateRetrieveSavingsTransaction(uriInfo);
    validateAppSelfServiceUserSavingsAccountMapping(accountId);
    return this.savingsAccountTransactionsApiResource.retrieveOne(
        accountId, transactionId, uriInfo);
  }

  @GET
  @Path("{accountId}/charges")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Savings Charges",
      description =
          "Lists Savings Charges\n\n"
              + "Example Requests:\n\n"
              + "self/savingsaccounts/1/charges\n\n"
              + "self/savingsaccounts/1/charges?chargeStatus=inactive\n\n"
              + "self/savingsaccounts/1/charges?fields=name,amountOrPercentage")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                array =
                    @ArraySchema(
                        schema =
                            @Schema(
                                implementation =
                                    SelfSavingsAccountApiResourceSwagger
                                        .GetSelfSavingsAccountsAccountIdChargesResponse.class))))
  })
  public String retrieveAllSavingsAccountCharges(
      @PathParam("accountId") @Parameter(description = "accountId") final Long accountId,
      @DefaultValue("all") @QueryParam("chargeStatus") @Parameter(description = "chargeStatus")
          final String chargeStatus,
      @Context final UriInfo uriInfo) {
    validateAppSelfServiceUserSavingsAccountMapping(accountId);
    return this.savingsAccountChargesApiResource.retrieveAllSavingsAccountCharges(
        accountId, chargeStatus, uriInfo);
  }

  @GET
  @Path("{accountId}/charges/{savingsAccountChargeId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a Savings account Charge",
      description =
          "Retrieves a Savings account Charge\n\n"
              + "Example Requests:\n\n"
              + "self/savingsaccounts/1/charges/5\n\n"
              + "self/savingsaccounts/1/charges/5?fields=name,amountOrPercentage")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfSavingsAccountApiResourceSwagger
                                .GetSelfSavingsAccountsAccountIdChargesSavingsAccountChargeIdResponse
                                .class)))
  })
  public String retrieveSavingsAccountCharge(
      @PathParam("accountId") @Parameter(description = "accountId") final Long accountId,
      @PathParam("savingsAccountChargeId") @Parameter(description = "savingsAccountChargeId")
          final Long savingsAccountChargeId,
      @Context final UriInfo uriInfo) {
    validateAppSelfServiceUserSavingsAccountMapping(accountId);
    return this.savingsAccountChargesApiResource.retrieveSavingsAccountCharge(
        accountId, savingsAccountChargeId, uriInfo);
  }

  private void validateAppSelfServiceUserSavingsAccountMapping(final Long accountId) {
    this.selfServiceOwnershipGuard.validateSavingsOwnership(accountId);
  }

  @GET
  @Path("template")
  @Produces({MediaType.APPLICATION_JSON})
  public String template(
      @QueryParam("clientId") final Long clientId,
      @QueryParam("productId") final Long productId,
      @Context final UriInfo uriInfo) {
    validateAppSelfServiceUserClientsMapping(clientId);
    Long groupId = null;
    boolean staffInSelectedOfficeOnly = false;
    return this.savingsAccountsApiResource.template(
        clientId, groupId, productId, staffInSelectedOfficeOnly, uriInfo);
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String submitSavingsAccountApplication(
      @QueryParam("command") final String commandParam,
      @Context final UriInfo uriInfo,
      final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    HashMap<String, Object> parameterMap =
        this.dataValidator.validateSavingsApplication(apiRequestBodyAsJson);
    final Long clientId =
        (Long) parameterMap.get(SelfSavingsAccountConstants.clientIdParameterName);
    validateAppSelfServiceUserClientsMapping(clientId);

    String responseJson = this.savingsAccountsApiResource.submitApplication(apiRequestBodyAsJson);

    Map<String, Object> contextData = new HashMap<>();
    Long savingsId = null;
    try {
      JsonObject reqJson = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
      if (reqJson.has("depositAmount"))
        contextData.put("depositAmount", reqJson.get("depositAmount").getAsBigDecimal());
      else if (reqJson.has("principal"))
        contextData.put("depositAmount", reqJson.get("principal").getAsBigDecimal());

      JsonObject resJson = JsonParser.parseString(responseJson).getAsJsonObject();
      if (resJson.has("savingsId")) savingsId = resJson.get("savingsId").getAsLong();
      else if (resJson.has("resourceId")) savingsId = resJson.get("resourceId").getAsLong();
    } catch (Exception e) {
      log.warn("Failed to parse savings application JSON for notification", e);
    }

    publishSavingsEvent(
        SelfServiceNotificationEvent.Type.SAVINGS_REQUESTED, savingsId, contextData, httpRequest);

    return responseJson;
  }

  @PUT
  @Path("{accountId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String modifySavingsAccountApplication(
      @PathParam("accountId") final Long accountId,
      @QueryParam("command") final String commandParam,
      final String apiRequestBodyAsJson,
      @Context HttpServletRequest httpRequest) {

    validateAppSelfServiceUserSavingsAccountMapping(accountId);
    HashMap<String, Object> attr =
        this.dataValidator.validateSavingsApplication(apiRequestBodyAsJson);
    // Fineract moves the account to the client (or group) given in the body, so the client must be
    // one of the user's own, as on submit, and a group cannot be set at all.
    final Long clientId =
        attr == null ? null : (Long) attr.get(SelfSavingsAccountConstants.clientIdParameterName);
    if (clientId != null) {
      validateAppSelfServiceUserClientsMapping(clientId);
    }
    rejectGroupId(apiRequestBodyAsJson);

    String responseJson =
        this.savingsAccountsApiResource.update(accountId, apiRequestBodyAsJson, commandParam);

    SelfServiceNotificationEvent.Type eventType =
        "withdrawnByApplicant".equalsIgnoreCase(commandParam)
            ? SelfServiceNotificationEvent.Type.SAVINGS_WITHDRAWN
            : SelfServiceNotificationEvent.Type.SAVINGS_UPDATED;

    Map<String, Object> contextData = new HashMap<>();
    try {
      JsonObject reqJson = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
      if (reqJson.has("depositAmount"))
        contextData.put("depositAmount", reqJson.get("depositAmount").getAsBigDecimal());
      else if (reqJson.has("principal"))
        contextData.put("depositAmount", reqJson.get("principal").getAsBigDecimal());
    } catch (Exception e) {
      log.warn("Failed to parse savings modification JSON for notification", e);
    }

    publishSavingsEvent(eventType, accountId, contextData, httpRequest);

    return responseJson;
  }

  private static void rejectGroupId(final String apiRequestBodyAsJson) {
    final JsonElement body = JsonParser.parseString(apiRequestBodyAsJson);
    if (body.isJsonObject() && body.getAsJsonObject().has(SavingsApiConstants.groupIdParamName)) {
      throw new PlatformApiDataValidationException(
          List.of(
              ApiParameterError.parameterError(
                  "validation.msg.savingsaccount.groupId.not.allowed",
                  "A self-service user cannot move a savings account to a group.",
                  SavingsApiConstants.groupIdParamName)));
    }
  }

  private void validateAppSelfServiceUserClientsMapping(final Long clientId) {
    this.selfServiceOwnershipGuard.validateClientOwnership(clientId);
  }

  private void publishSavingsEvent(
      SelfServiceNotificationEvent.Type type,
      Long savingsId,
      Map<String, Object> contextData,
      HttpServletRequest httpRequest) {
    try {
      AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
      String mobileNumber = extractMobile(user);
      boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);

      contextData.put("savingsId", savingsId != null ? savingsId : "");

      applicationEventPublisher.publishEvent(
          SelfServiceNotificationEvent.withTenantContext(
              this,
              type,
              user.getId(),
              user.getFirstname(),
              user.getLastname(),
              user.getUsername(),
              user.getEmail(),
              mobileNumber,
              emailMode,
              extractClientIp(httpRequest),
              LocaleContextHolder.getLocale(),
              contextData));
    } catch (Exception e) {
      log.warn("Failed to publish {} notification event", type, e);
    }
  }

  private String extractMobile(AppSelfServiceUser user) {
    if (user == null || user.getAppUserClientMappings() == null) return null;
    return user.getAppUserClientMappings().stream()
        .map(AppSelfServiceUserClientMapping::getClient)
        .filter(Objects::nonNull)
        .map(Client::getMobileNo)
        .filter(StringUtils::isNotBlank)
        .findFirst()
        .orElse(null);
  }

  private String extractClientIp(HttpServletRequest httpRequest) {
    if (httpRequest == null) return null;
    String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
    if (StringUtils.isNotBlank(xForwardedFor)) {
      String firstToken = xForwardedFor.split(",")[0].trim();
      if (StringUtils.isNotBlank(firstToken)) return firstToken;
    }
    return httpRequest.getRemoteAddr();
  }
}