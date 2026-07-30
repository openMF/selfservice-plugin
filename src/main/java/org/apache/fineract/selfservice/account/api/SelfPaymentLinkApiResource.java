/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.PaymentLinkConfirmRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkPrepareRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkResponse;
import org.apache.fineract.selfservice.account.service.PaymentLinkService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Component;

@Path("/v1/self/paymentlink")
@Component
@Tag(
    name = "Self Payment Request",
    description = "Create payment/checkout request via external payment provider")
@RequiredArgsConstructor
public class SelfPaymentLinkApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final PaymentLinkService paymentLinkService;
  private final DefaultToApiJsonSerializer<PaymentLinkResponse> toApiJsonSerializer;
  private final Gson gson = new Gson();

  @POST
    @Path("/prepare")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
        summary = "Prepare Payment Link",
        description = "Calculates and responds with the fee charged for the PAYMENT_LINK transfer method. OTP is not required."
    )
    public String prepare(final String apiRequestBodyAsJson) {
        context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
        PaymentLinkPrepareRequest request = gson.fromJson(apiRequestBodyAsJson, PaymentLinkPrepareRequest.class);
        AccountTransferQuoteResponse response = paymentLinkService.preparePaymentLink(request);
        return toApiJsonSerializer.serialize(response);
    }

    @POST
    @Path("/confirm")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
        summary = "Confirm Payment Link",
        description = "Confirms the payment link transfer. The request must include the transferMethod to calculate the fee. OTP is not required."
    )
    public String confirm(final String apiRequestBodyAsJson) {
        context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
        AppSelfServiceUser appSelfServiceUser = context.authenticatedSelfServiceUser();
        PaymentLinkConfirmRequest request = gson.fromJson(apiRequestBodyAsJson, PaymentLinkConfirmRequest.class);
        PaymentLinkResponse response = paymentLinkService.confirmPaymentLink(request);
        return toApiJsonSerializer.serialize(response);
    }
}
