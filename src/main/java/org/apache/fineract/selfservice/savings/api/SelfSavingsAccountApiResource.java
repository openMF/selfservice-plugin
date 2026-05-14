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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.savings.api.SavingsAccountChargesApiResource;
import org.apache.fineract.portfolio.savings.api.SavingsAccountTransactionsApiResource;
import org.apache.fineract.portfolio.savings.api.SavingsAccountsApiResource;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.selfservice.client.service.AppSelfServiceUserClientMapperReadService;
import org.apache.fineract.selfservice.savings.data.SelfSavingsAccountConstants;
import org.apache.fineract.selfservice.savings.data.SelfSavingsDataValidator;
import org.apache.fineract.selfservice.savings.service.AppuserSavingsMapperReadService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Path("/v1/self/savingsaccounts")
@Component
@Tag(name = "Self Savings Account", description = "")
@RequiredArgsConstructor
public class SelfSavingsAccountApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SavingsAccountsApiResource savingsAccountsApiResource;
  private final SavingsAccountChargesApiResource savingsAccountChargesApiResource;
  private final SavingsAccountTransactionsApiResource savingsAccountTransactionsApiResource;
  private final AppuserSavingsMapperReadService appuserSavingsMapperReadService;
  private final SelfSavingsDataValidator dataValidator;
  private final AppSelfServiceUserClientMapperReadService appUserClientMapperReadService;

  @GET
    @Path("{accountId}")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
        summary = "Retrieve a savings account",
        description = "Retrieves a savings account\n\n"
            + "Example Requests:\n\n"
            + "self/savingsaccounts/1\n\n"
            + "self/savingsaccounts/1?associations=transactions\n\n"
            + "self/savingsaccounts/1?associations=transactions&month=5&year=2026\n\n"
            + "self/savingsaccounts/1?associations=transactions&lastTransactions=10\n\n"
            + "self/savingsaccounts/1?associations=transactions&month=5&year=2026&lastTransactions=5")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(schema = @Schema(
                implementation = SelfSavingsAccountApiResourceSwagger.GetSelfSavingsAccountsResponse.class)))
    })
    public SavingsAccountData retrieveSavings(
        @PathParam("accountId") @Parameter(description = "accountId") final Long accountId,
        @DefaultValue("all") @QueryParam("chargeStatus") @Parameter(description = "chargeStatus")
            final String chargeStatus,

        // Date filters
        @QueryParam("month") @Parameter(description = "Filter transactions by month (1-12)")
            final Integer month,
        @QueryParam("year") @Parameter(description = "Filter transactions by year")
            final Integer year,

        // New: Last N transactions filter
        @QueryParam("lastTransactions") @Parameter(description = "Return only the last N transactions (most recent)")
            final Integer lastTransactions,

        @Context final UriInfo uriInfo) {

        this.dataValidator.validateRetrieveSavings(uriInfo);
        validateAppSelfServiceUserSavingsAccountMapping(accountId);

        final boolean staffInSelectedOfficeOnly = false;

        // Build date range for core Fineract API
        String dateRange = null;
        if (month != null && year != null) {
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("Month must be between 1 and 12");
            }
            String fromDate = String.format("%d-%02d-01", year, month);
            String toDate = String.format("%d-%02d-%02d", year, month, getLastDayOfMonth(year, month));
            dateRange = fromDate + "," + toDate;
        }

        SavingsAccountData savingsAccountData = this.savingsAccountsApiResource.retrieveOne(
            accountId, staffInSelectedOfficeOnly, chargeStatus, dateRange, uriInfo);

        // ====================== APPLY FILTERS ======================
        Collection<SavingsAccountTransactionData> transactions = savingsAccountData.getTransactions();

        if (!CollectionUtils.isEmpty(transactions)) {
            List<SavingsAccountTransactionData> filtered = new ArrayList<>(transactions);

            // 1. Filter by Month & Year (if provided)
            if (month != null && year != null) {
                filtered = filtered.stream()
                    .filter(Objects::nonNull)
                    .filter(t -> {
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

            // 2. Filter Last N Transactions (most recent first)
            if (lastTransactions != null && lastTransactions > 0) {
                // Sort by transaction date descending (newest first)
                filtered.sort((t1, t2) -> {
                    LocalDate d1 = t1.getTransactionDate() != null ? t1.getTransactionDate() : t1.getDate();
                    LocalDate d2 = t2.getTransactionDate() != null ? t2.getTransactionDate() : t2.getDate();
                    return d2.compareTo(d1); // descending
                });

                // Limit to last N transactions
                if (filtered.size() > lastTransactions) {
                    filtered = filtered.subList(0, lastTransactions);
                }
            }

            // Apply filtered list using reflection (since SavingsAccountData has no setter)
            try {
                Field transactionsField = SavingsAccountData.class.getDeclaredField("transactions");
                transactionsField.setAccessible(true);
                transactionsField.set(savingsAccountData, filtered);
            } catch (Exception e) {
                // Log in production environment
                System.err.println("Warning: Could not set filtered transactions - " + e.getMessage());
            }
        }

        return savingsAccountData;
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
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    final boolean isMappedSavings =
        this.appuserSavingsMapperReadService.isSavingsMappedToUser(accountId, user.getId());
    if (!isMappedSavings) {
      throw new SavingsAccountNotFoundException(accountId);
    }
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
      final String apiRequestBodyAsJson) {
    HashMap<String, Object> parameterMap =
        this.dataValidator.validateSavingsApplication(apiRequestBodyAsJson);
    final Long clientId =
        (Long) parameterMap.get(SelfSavingsAccountConstants.clientIdParameterName);
    validateAppSelfServiceUserClientsMapping(clientId);
    return this.savingsAccountsApiResource.submitApplication(apiRequestBodyAsJson);
  }

  @PUT
  @Path("{accountId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String modifySavingsAccountApplication(
      @PathParam("accountId") final Long accountId,
      @QueryParam("command") final String commandParam,
      final String apiRequestBodyAsJson) {
    validateAppSelfServiceUserSavingsAccountMapping(accountId);
    this.dataValidator.validateSavingsApplication(apiRequestBodyAsJson);
    return this.savingsAccountsApiResource.update(accountId, apiRequestBodyAsJson, commandParam);
  }

  private void validateAppSelfServiceUserClientsMapping(final Long clientId) {
    AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();
    final boolean mappedClientId =
        this.appUserClientMapperReadService.isClientMappedToSelfServiceUser(clientId, user.getId());
    if (!mappedClientId) {
      throw new ClientNotFoundException(clientId);
    }
  }
}
