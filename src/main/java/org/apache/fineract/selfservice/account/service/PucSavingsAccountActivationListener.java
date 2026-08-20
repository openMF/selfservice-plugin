package org.apache.fineract.selfservice.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.selfservice.account.data.PucAddAccountRequest;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class PucSavingsAccountActivationListener {

    private final PucExternalApiClient pucExternalApiClient;
    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
    private final ClientReadPlatformService clientReadPlatformService;

    /**
     * Escucha el evento oficial de activación de cuentas de ahorro en Fineract
     */
    @Async
    @EventListener
    public void handleSavingsAccountActivation(Object event) {
        String eventName = event.getClass().getSimpleName();

        // Mapeo del evento de activación de Fineract / Finecko
        if (!"SavingsActivateBusinessEvent".equals(eventName))
        {
            return;
        }

        log.info("Evento capturado: [{}]. Iniciando registro automático de cuenta en el PUC...", eventName);

        try {
            // 1. Extraer el ID de la cuenta desde el evento
            Long savingsAccountId = extractSavingsAccountId(event);
            if (savingsAccountId == null) {
                log.warn("No se pudo extraer el ID de la cuenta desde el evento {}", eventName);
                return;
            }

            // 2. Consultar la información de la cuenta en Fineract
            SavingsAccountData accountData = this.savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
            if (accountData == null) {
                log.warn("No se encontró la cuenta de ahorro con ID: {}", savingsAccountId);
                return;
            }

            // 3. Consultar la información del Cliente para obtener la Identificación/Cédula
            ClientData clientData = this.clientReadPlatformService.retrieveOne(accountData.getClientId());
            if (clientData == null) {
                log.warn("No se encontraron datos del cliente con ID: {}", accountData.getClientId());
                return;
            }

            // 4. Construir la solicitud para el PUC / KINDO
            PucAddAccountRequest pucRequest = new PucAddAccountRequest();

            // IBAN (externalId de la cuenta) -> "CR19037300220010000086"
            String iban = extractExternalIdValue(accountData.getExternalId());
            if (iban.isBlank()) {
                iban = accountData.getAccountNo();
            }

            // Account Number
            String accountNumber = (accountData.getAccountNo() != null) ? accountData.getAccountNo() : iban;

            // Holder Name
            String holder = (accountData.getClientName() != null && !accountData.getClientName().isBlank())
                    ? accountData.getClientName()
                    : clientData.getDisplayName();

            // Holder ID (Cédula/Identificación)
            String holderId = extractExternalIdValue(clientData.getExternalId());

            // Currency Code
            String currencyCode = (accountData.getCurrency() != null && accountData.getCurrency().getCode() != null)
                    ? accountData.getCurrency().getCode()
                    : "CRC";

            // Seteo de propiedades en el DTO del PUC
            pucRequest.setAccountNumber(iban);
            pucRequest.setHolder(holder);
            pucRequest.setHolderId(holderId);
            pucRequest.setCurrencyCode(currencyCode);
            pucRequest.setAccountType("CAR");

            log.info("Enviando AddAccount a PUC -> IBAN: {}, AccountNo: {}, Titular: {}, Cédula: {}, Moneda: {}, Tipo: CAR",
                    iban, accountNumber, holder, holderId, currencyCode);

            // 5. Enviar la petición al cliente REST de PUC
            String response = this.pucExternalApiClient.addAccount(pucRequest);
            log.info("Respuesta del servicio PUC para IBAN {}: {}", iban, response);

        } catch (Exception e) {
            log.error("Error procesando el registro en PUC tras evento de activación ({})", eventName, e);
        }
    }

    /**
     * Extrae de forma segura el valor en String de un objeto ExternalId o String.
     */
    private String extractExternalIdValue(Object externalIdObj) {
        if (externalIdObj == null) {
            return "";
        }
        if (externalIdObj instanceof ExternalId extId) {
            return extId.isEmpty() ? "" : extId.getValue();
        }
        if (externalIdObj instanceof String str) {
            return str;
        }
        return externalIdObj.toString();
    }

    /**
     * Extrae el ID de la cuenta desde el evento mediante reflexión
     */
    private Long extractSavingsAccountId(Object event) {
        try {
            var method = event.getClass().getMethod("getSavingsAccount");
            Object accountObj = method.invoke(event);
            if (accountObj instanceof SavingsAccount account) {
                return account.getId();
            }
        } catch (Exception e) {
            try {
                var method = event.getClass().getMethod("getAccountId");
                Object idObj = method.invoke(event);
                if (idObj instanceof Long id) {
                    return id;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}