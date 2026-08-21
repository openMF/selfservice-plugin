/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.data.RemittanceConfirmRequest;
import org.apache.fineract.selfservice.account.data.RemittancePayoutRequest;
import org.apache.fineract.selfservice.account.data.RemittancePrepareRequest;
import org.apache.fineract.selfservice.account.data.RemittanceRecipientRequest;
import org.apache.fineract.selfservice.account.data.RemittanceResponse;
import org.apache.fineract.selfservice.account.service.RemittanceService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/remittance")
@Component
@Tag(name = "Self Remittance", description = "Remittance send and payout operations via external remittance providers (RIA, TRANZMIT, etc.)")
@RequiredArgsConstructor
public class SelfRemittanceApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final RemittanceService remittanceService;
  private final DefaultToApiJsonSerializer<RemittanceResponse> toApiJsonSerializer;
  private final Gson gson = new Gson();

  @GET
  @Path("/vendors")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Get available remittance vendors",
      description = "Returns list of configured vendors for SEND or PAYOUT operations.")
  public String getVendors(
      @QueryParam("operationType") @Parameter(description = "SEND or PAYOUT") String operationType) {
    context.authenticatedSelfServiceUser().validateHasReadPermission("ACCOUNTTRANSFER");
    return remittanceService.getAvailableVendors(operationType);
  }

  @GET
  @Path("/products")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Get product types")
  public String getProducts(
      @QueryParam("vendor") String vendor,
      @QueryParam("acceptLanguage") String acceptLanguage) {
    context.authenticatedSelfServiceUser().validateHasReadPermission("ACCOUNTTRANSFER");
    return remittanceService.getProducts(vendor, acceptLanguage);
  }

  @GET
  @Path("/countries")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Get supported countries")
  public String getCountries(
      @QueryParam("vendor") String vendor,
      @QueryParam("acceptLanguage") String acceptLanguage) {
    context.authenticatedSelfServiceUser().validateHasReadPermission("ACCOUNTTRANSFER");
    return remittanceService.getCountries(vendor, acceptLanguage);
  }

  @GET
  @Path("/countries/deliverymethods")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Get delivery methods for a country")
  public String getDeliveryMethods(
      @QueryParam("vendor") String vendor,
      @QueryParam("abbrev") String countryAbbrev,
      @QueryParam("productId") String productId,
      @QueryParam("acceptLanguage") String acceptLanguage) {
    context.authenticatedSelfServiceUser().validateHasReadPermission("ACCOUNTTRANSFER");
    return remittanceService.getDeliveryMethods(vendor, countryAbbrev, productId, acceptLanguage);
  }

  @POST
  @Path("/prepare")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Prepare remittance (quote/fee)",
      description = "Prepares a SEND remittance, calculates fees if applicable. OTP not required.")
  public String prepare(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittancePrepareRequest request =
        gson.fromJson(apiRequestBodyAsJson, RemittancePrepareRequest.class);
    RemittanceResponse response = remittanceService.prepareRemittance(request);
    return toApiJsonSerializer.serialize(response);
  }

  @POST
  @Path("/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Confirm / create remittance",
      description = "Confirms and submits a SEND remittance to the external provider.")
  public String confirm(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittanceConfirmRequest request =
        gson.fromJson(apiRequestBodyAsJson, RemittanceConfirmRequest.class);
    RemittanceResponse response = remittanceService.confirmRemittance(request);
    return toApiJsonSerializer.serialize(response);
  }

  @GET
  @Path("/{vendor}/transactions/{id}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Find remittance by PIN / ID",
      description = "Looks up a remittance transaction (typically for PAYOUT flow).")
  public String find(
      @PathParam("vendor") String vendor,
      @PathParam("id") String id) {
    context.authenticatedSelfServiceUser().validateHasReadPermission("ACCOUNTTRANSFER");
    RemittanceResponse response = remittanceService.findRemittance(vendor, id);
    return toApiJsonSerializer.serialize(response);
  }

  @POST
  @Path("/{vendor}/transactions/{id}/recipient")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Validate recipient data for payout")
  public String validateRecipient(
      @PathParam("vendor") String vendor,
      @PathParam("id") String id,
      final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittanceRecipientRequest request =
        gson.fromJson(apiRequestBodyAsJson, RemittanceRecipientRequest.class);
    return remittanceService.validateRecipient(vendor, id, request);
  }

  @POST
  @Path("/{vendor}/transactions/{id}/payout-assignment")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Assign transaction for payout")
  public String assignPayout(
      @PathParam("vendor") String vendor,
      @PathParam("id") String id,
      final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittancePayoutRequest request =
        gson.fromJson(apiRequestBodyAsJson, RemittancePayoutRequest.class);
    RemittanceResponse response = remittanceService.assignPayout(vendor, id, request);
    return toApiJsonSerializer.serialize(response);
  }

  @POST
  @Path("/{vendor}/transactions/{id}/payout-confirmation")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Confirm payout of transaction")
  public String confirmPayout(
      @PathParam("vendor") String vendor,
      @PathParam("id") String id,
      final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittancePayoutRequest request =
        gson.fromJson(apiRequestBodyAsJson, RemittancePayoutRequest.class);
    RemittanceResponse response = remittanceService.confirmPayout(vendor, id, request);
    return toApiJsonSerializer.serialize(response);
  }

  @DELETE
  @Path("/{vendor}/transactions/{id}/payout-assignment")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Unassign transaction from payout")
  public String unassignPayout(
      @PathParam("vendor") String vendor,
      @PathParam("id") String id,
      final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittancePayoutRequest request =
        gson.fromJson(apiRequestBodyAsJson != null ? apiRequestBodyAsJson : "{}", RemittancePayoutRequest.class);
    RemittanceResponse response = remittanceService.unassignPayout(vendor, id, request);
    return toApiJsonSerializer.serialize(response);
  }

  @POST
  @Path("/{vendor}/transactions/{id}/payout-rejection")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(summary = "Reject payout of transaction")
  public String rejectPayout(
      @PathParam("vendor") String vendor,
      @PathParam("id") String id,
      final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
    RemittancePayoutRequest request =
        gson.fromJson(apiRequestBodyAsJson, RemittancePayoutRequest.class);
    RemittanceResponse response = remittanceService.rejectPayout(vendor, id, request);
    return toApiJsonSerializer.serialize(response);
  }
}
