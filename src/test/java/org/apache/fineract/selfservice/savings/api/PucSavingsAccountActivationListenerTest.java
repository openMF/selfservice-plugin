package org.apache.fineract.selfservice.savings.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.selfservice.account.data.PucAddAccountRequest;
import org.apache.fineract.selfservice.account.service.PucExternalApiClient;
import org.apache.fineract.selfservice.account.service.PucSavingsAccountActivationListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class PucSavingsAccountActivationListenerTest {

    @Mock
    private PucExternalApiClient pucExternalApiClient;

    @Mock
    private SavingsAccountReadPlatformService savingsAccountReadPlatformService;

    @Mock
    private ClientReadPlatformService clientReadPlatformService;

    @InjectMocks
    private PucSavingsAccountActivationListener listener;

    private SavingsAccountData mockAccountData;
    private ClientData mockClientData;

    @BeforeEach
    void setUp() {
        // Simular datos de la cuenta en Fineract
        mockAccountData = mock(SavingsAccountData.class);
        when(mockAccountData.getClientId()).thenReturn(10L);
        when(mockAccountData.getAccountNo()).thenReturn("00012345");
        when(mockAccountData.getExternalId()).thenReturn("CR12345678901234567890");
        when(mockAccountData.getSavingsProductName()).thenReturn("Cuenta de Ahorros Santander");

        CurrencyData currencyData = new CurrencyData("CRC", "Colón Costa Rica", 2, 0, "c", "CRC");
        when(mockAccountData.getCurrency()).thenReturn(currencyData);

        // Simular datos del cliente en Fineract
        mockClientData = mock(ClientData.class);
        when(mockClientData.getDisplayName()).thenReturn("Juan Pérez");
        when(mockClientData.getExternalId()).thenReturn(new ExternalId("CR92037300110010000087"));
    }

    @Test
    @DisplayName("Debe capturar el evento de activación y enviar la solicitud correcta a PUC")
    void testHandleSavingsAccountActivation_Success() {
        // Arrange
        Long accountId = 100L;
        SavingsAccount mockSavingsAccount = mock(SavingsAccount.class);
        when(mockSavingsAccount.getId()).thenReturn(accountId);

        // Crear un evento simulado que exponga getSavingsAccount()
        Object mockEvent = new Object() {
            public SavingsAccount getSavingsAccount() {
                return mockSavingsAccount;
            }
        };

        when(savingsAccountReadPlatformService.retrieveOne(accountId)).thenReturn(mockAccountData);
        when(clientReadPlatformService.retrieveOne(10L)).thenReturn(mockClientData);
        when(pucExternalApiClient.addAccount(any(PucAddAccountRequest.class))).thenReturn("{\"success\":true}");

        // Act
        listener.handleSavingsAccountActivation(mockEvent);

        // Assert: Capturar la petición enviada a PUC para validar sus valores
        ArgumentCaptor<PucAddAccountRequest> captor = ArgumentCaptor.forClass(PucAddAccountRequest.class);
        verify(pucExternalApiClient).addAccount(captor.capture());

        PucAddAccountRequest sentRequest = captor.getValue();
        assertEquals("CR12345678901234567890", sentRequest.getAccountNumber());
        assertEquals("00012345", sentRequest.getAccountNumber());
        assertEquals("Juan Pérez", sentRequest.getHolder());
        assertEquals("01-0987-0654", sentRequest.getHolderId());
        assertEquals("CRC", sentRequest.getCurrencyCode());
        assertEquals("AHORROS", sentRequest.getAccountType());
    }
}
