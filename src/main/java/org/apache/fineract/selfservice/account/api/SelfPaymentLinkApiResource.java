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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.PaymentLinkConfirmRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkPrepareRequest;
import org.apache.fineract.selfservice.account.data.PaymentLinkResponse;
import org.apache.fineract.selfservice.account.service.PaymentLinkService;
import org.apache.fineract.selfservice.notification.NotificationContext;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
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
    private final ApplicationEventPublisher applicationEventPublisher;
    private final NotificationDeliveryModeUtil notificationDeliveryModeUtil;

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
    public String confirm(
            final String apiRequestBodyAsJson,
            @Context HttpServletRequest httpRequest) {

        context.authenticatedSelfServiceUser().validateHasCreatePermission("ACCOUNTTRANSFER");
        AppSelfServiceUser appSelfServiceUser = context.authenticatedSelfServiceUser();
        PaymentLinkConfirmRequest request = gson.fromJson(apiRequestBodyAsJson, PaymentLinkConfirmRequest.class);
        PaymentLinkResponse response = paymentLinkService.confirmPaymentLink(request);

        // Publicar evento pasando el objeto request desglosado
        publishPaymentLinkNotificationEvent(appSelfServiceUser, request, response, httpRequest);

        return toApiJsonSerializer.serialize(response);
    }

    private void publishPaymentLinkNotificationEvent(
            AppSelfServiceUser user,
            PaymentLinkConfirmRequest request,
            PaymentLinkResponse response,
            HttpServletRequest httpRequest) {

        if (user == null || response == null) {
            return;
        }

        String mobileNumber = extractMobile(user);
        boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);
        String ipAddress = extractClientIp(httpRequest);

        // Formatear el monto leyendo los datos desglosados del objeto 'request'
        String montoFormateado = "";
        if (request != null) {
            String currency = request.getCurrency() != null ? request.getCurrency() : "";
            String amount = request.getAmount() != null ? request.getAmount().toString() : "";
            montoFormateado = (currency + " " + amount).trim();
        }

        // Mapeo de parámetros para la plantilla HTML de la notificación
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("firstname", user.getFirstname());
        contextData.put("lastname", user.getLastname());
        contextData.put("numero", response.getCheckoutId() != null ? response.getCheckoutId() : "");
        contextData.put("monto", montoFormateado);
        contextData.put("estado", response.getPaymentStatus() != null ? response.getPaymentStatus() : "");
        contextData.put("paymentUrl", response.getPaymentUrl() != null ? response.getPaymentUrl() : "");

        SelfServiceNotificationEvent.Type eventType = SelfServiceNotificationEvent.Type.PAYMENT_LINK_CREATED;

        try (NotificationContext.Scope ignored = NotificationContext.bind(eventType.name())) {
            applicationEventPublisher.publishEvent(
                    SelfServiceNotificationEvent.withTenantContext(
                            this,
                            eventType,
                            user.getId(),
                            user.getFirstname(),
                            user.getLastname(),
                            user.getUsername(),
                            user.getEmail(),
                            mobileNumber,
                            emailMode,
                            ipAddress,
                            httpRequest != null ? httpRequest.getLocale() : null,
                            contextData));
            log.info("Payment Link notification published for user: {}", user.getUsername());
        } catch (Exception e) {
            log.warn("Failed to publish PAYMENT_LINK_CREATED notification event for user: {}", user.getUsername(), e);
        }
    }

    private String extractClientIp(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xForwardedFor)) {
            String firstToken = xForwardedFor.split(",")[0].trim();
            if (StringUtils.isNotBlank(firstToken)) {
                return firstToken;
            }
        }
        String realIp = httpRequest.getHeader("X-Real-Ip");
        if (StringUtils.isNotBlank(realIp)) {
            return realIp.trim();
        }
        return httpRequest.getRemoteAddr();
    }

    private String extractMobile(AppSelfServiceUser user) {
        if (user == null || user.getAppUserClientMappings() == null) {
            return null;
        }
        return user.getAppUserClientMappings().stream()
                .map(AppSelfServiceUserClientMapping::getClient)
                .filter(Objects::nonNull)
                .map(Client::getMobileNo)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }
}