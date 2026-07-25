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
import org.apache.fineract.selfservice.account.data.PaymentLinkRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkResponse;
import org.apache.fineract.selfservice.account.service.PaymentLinkExternalService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/payments/checkout-links")
@Component
@Tag(
    name = "Self Payment Links",
    description = "Create payment/checkout links via external payment provider (Apolo/OnvoPay)")
@RequiredArgsConstructor
public class SelfPaymentLinkApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final PaymentLinkExternalService paymentLinkService;
  private final DefaultToApiJsonSerializer<PaymentLinkResponse> toApiJsonSerializer;
  private final Gson gson = new Gson();

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create a payment checkout link",
      description =
          "Calls the configured external PaymentLinkService and returns checkoutId + paymentUrl.")
  public String createCheckoutLink(final String apiRequestBodyAsJson) {
    // Re-use an existing transfer-related permission (or create a dedicated one via Liquibase if
    // preferred)
    context.authenticatedSelfServiceUser().validateHasCreatePermission("SSACCOUNTTRANSFER");

    PaymentLinkRequest request = gson.fromJson(apiRequestBodyAsJson, PaymentLinkRequest.class);
    PaymentLinkResponse response = paymentLinkService.createPaymentLink(request);
    return toApiJsonSerializer.serialize(response);
  }
}
