/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.TransactionDateManagementService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountNotFoundException;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferQuoteResponse;
import org.apache.fineract.selfservice.account.data.FeeCollectionRequest;
import org.apache.fineract.selfservice.account.data.FeeCollectionResult;
import org.apache.fineract.selfservice.account.data.ResendOtpRequest;
import org.apache.fineract.selfservice.account.data.SelfAccountTransferDataValidator;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountForFeesRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountTransferRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceSameBankTransferAuditRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAuditRepository;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfAccountTransferWritePlatformServiceImplTest {

  private static final Long USER_ID = 99L;
  private static final Long OWN_CLIENT_ID = 101L;
  private static final Long SECOND_CLIENT_ID = 202L;
  private static final Long FOREIGN_CLIENT_ID = 303L;
  private static final Long SAVINGS_ID = 11L;
  private static final String OTP = "123456";
  private static final String IBAN = "CR05015202001026284066";
  private static final LocalDateTime TEST_NOW = LocalDateTime.of(2026, 1, 2, 10, 0, 0);
  private static final String TEST_FINERACT_DATE = "02 January 2026";

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private AccountTransferQuoteService quoteService;
  @Mock private SinpeExternalApiClient sinpeExternalApiClient;
  @Mock private SelfServiceRegistrationRepository registrationRepository;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @Mock private Environment env;
  @Mock private FromJsonHelper fromApiJsonHelper;
  @Mock private NotificationCooldownCache notificationCooldownCache;
  @Mock private AccountTransfersWritePlatformService accountTransfersWritePlatformService;
  @Mock private ExternalIdFactory externalIdFactory;
  @Mock private SelfAccountTransferDataValidator dataValidator;
  @Mock private SelfBeneficiariesTPTReadPlatformService tptBeneficiaryReadPlatformService;
  @Mock private ConfigurationDomainService configurationDomainService;
  @Mock private AccountTransfersReadPlatformService accountTransfersReadPlatformService;
  @Mock private ClientReadPlatformService clientReadPlatformService;
  @Mock private OfficeReadPlatformService officeReadPlatformService;
  @Mock private PinExternalTransferService pinExternalTransferService;
  @Mock private SelfServiceAccountForFeesRepository externalServicePropertiesRepository;
  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private SavingsAccountAssembler savingsAccountAssembler;
  @Mock private LoanAssembler loanAssembler;
  @Mock private SavingsAccountRepositoryWrapper savingsAccountRepositoryWrapper;
  @Mock private SelfServiceSameBankTransferAuditRepository sameBankTransferAuditRepository;
  @Mock private SavingsAccountTransactionRepository savingsAccountTransactionRepository;
  @Mock private SelfServiceAccountTransferRepository selfServiceAccountTransferRepository;
  @Mock private SelfServiceTransferAuditRepository transferAuditRepository;
  @Mock private SelfServiceFeeCollectionService feeCollectionService;
  @Mock private TransactionDateUtil transactionDateUtil;
  @Mock private TransactionDateManagementService transactionDateManagementService;
  @Mock private HttpServletRequest httpRequest;

  @InjectMocks private SelfAccountTransferWritePlatformServiceImpl service;

  private AppSelfServiceUser user;
  private Client ownClient;
  private Client secondClient;
  private Client foreignClient;

  @BeforeEach
  void setUp() {
    user = mock(AppSelfServiceUser.class);
    ownClient = client(OWN_CLIENT_ID);
    secondClient = client(SECOND_CLIENT_ID);
    foreignClient = client(FOREIGN_CLIENT_ID);

    lenient().when(context.authenticatedSelfServiceUser()).thenReturn(user);
    lenient().when(user.getId()).thenReturn(USER_ID);
    lenient().when(user.getUsername()).thenReturn("selfuser");
    lenient().when(user.getEmail()).thenReturn("self@example.test");
    lenient().when(user.getFirstname()).thenReturn("Self");
    lenient().when(user.getLastname()).thenReturn("User");
    Set<AppSelfServiceUserClientMapping> defaultMappings = mappings(ownClient);
    lenient().when(user.getAppUserClientMappings()).thenReturn(defaultMappings);
    lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    lenient().when(transactionDateUtil.getCurrentTenantLocalDateTime()).thenReturn(TEST_NOW);
    lenient()
        .when(transactionDateUtil.getCurrentDateForFineract(anyString(), anyString()))
        .thenReturn(TEST_FINERACT_DATE);
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void prepareTransfer_acceptsOwnedSourceAccount() throws Exception {
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));

    assertDoesNotThrow(() -> service.prepareTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(ownClient));
    verify(ownSavings).getWithdrawableBalance();
  }

  @Test
  void prepareTransfer_rejectsForeignSourceBeforeSideEffects() throws Exception {
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, foreignSavings);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.prepareTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(foreignSavings, never()).getWithdrawableBalance();
    verifyNoInteractions(
        quoteService,
        pinExternalTransferService,
        sinpeExternalApiClient,
        registrationRepository,
        feeCollectionService,
        accountTransfersWritePlatformService,
        applicationEventPublisher);
  }

  @Test
  void prepareTransfer_rejectsMissingSourceBeforeAccountResolutionOrSideEffects() throws Exception {
    AccountTransferPrepareRequest request = prepareRequest(null);

    assertThrows(IllegalArgumentException.class, () -> service.prepareTransfer(request));

    verifyNoInteractions(
        savingsAccountAssembler,
        savingsAccountRepositoryWrapper,
        quoteService,
        registrationRepository,
        accountTransfersWritePlatformService,
        applicationEventPublisher);
  }

  @Test
  void quoteTransfer_acceptsExternalIdSourceAccount() throws Exception {
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    externalSourceAccount("SRC-EXT", ownSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));
    when(registrationRepository.markOldOtpsAsConsumed(
            eq(OWN_CLIENT_ID),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class)))
        .thenReturn(0);

    Object response = assertDoesNotThrow(() -> service.quoteTransfer(prepareRequest("SRC-EXT")));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(ownClient));
    verify(registrationRepository).saveAndFlush(any(SelfServiceRegistration.class));
    verify(applicationEventPublisher).publishEvent(any());
    assertFalse(response.toString().toLowerCase().contains("otp"));
  }

  @Test
  void quoteTransfer_acceptsSourceMappedToSecondClientAndUsesSecondClientForOtp() throws Exception {
    useClientMappings(ownClient, secondClient);
    when(ownClient.getMobileNo()).thenReturn("11111111");
    when(secondClient.getMobileNo()).thenReturn("22222222");
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, secondClientSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));
    when(registrationRepository.markOldOtpsAsConsumed(
            eq(SECOND_CLIENT_ID),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class)))
        .thenReturn(0);

    assertDoesNotThrow(() -> service.quoteTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(secondClient));
    verify(registrationRepository)
        .markOldOtpsAsConsumed(
            eq(SECOND_CLIENT_ID),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class));
    verify(registrationRepository, never())
        .markOldOtpsAsConsumed(
            eq(OWN_CLIENT_ID),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class));
    ArgumentCaptor<SelfServiceRegistration> registrationCaptor =
        ArgumentCaptor.forClass(SelfServiceRegistration.class);
    verify(registrationRepository).saveAndFlush(registrationCaptor.capture());
    assertEquals(SECOND_CLIENT_ID, registrationCaptor.getValue().getClient().getId());
    ArgumentCaptor<SelfServiceNotificationEvent> eventCaptor =
        ArgumentCaptor.forClass(SelfServiceNotificationEvent.class);
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    SelfServiceNotificationEvent event = eventCaptor.getValue();
    assertEquals("22222222", event.getMobileNumber());
  }

  @Test
  void quoteTransfer_rejectsForeignSourceBeforeOtpGeneration() throws Exception {
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, foreignSavings);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.quoteTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(foreignSavings, never()).getWithdrawableBalance();
    verifyNoInteractions(quoteService, registrationRepository, applicationEventPublisher);
  }

  @Test
  void quoteTransfer_rejectsForeignExternalIdBeforeOtpGeneration() throws Exception {
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    externalSourceAccount("FOREIGN-EXT", foreignSavings);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.quoteTransfer(prepareRequest("FOREIGN-EXT")));

    verify(foreignSavings, never()).getWithdrawableBalance();
    verifyNoInteractions(quoteService, registrationRepository, applicationEventPublisher);
  }

  @Test
  void quoteTransfer_rejectsInvalidIdentifierWithSafeSourceErrorBeforeOtpGeneration()
      throws Exception {
    when(externalIdFactory.create("%%%"))
        .thenThrow(new PlatformApiDataValidationException(List.of()));

    PlatformApiDataValidationException exception =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.quoteTransfer(prepareRequest("%%%")));

    assertEquals(
        "validation.msg.accounttransfer.fromAccount.invalid.source.account",
        exception.getErrors().get(0).getUserMessageGlobalisationCode());
    assertEquals("fromAccount", exception.getErrors().get(0).getParameterName());
    verifyNoInteractions(quoteService, registrationRepository, applicationEventPublisher);
  }

  @Test
  void quoteTransfer_acceptsIbanSourceMappedToSecondClient() throws Exception {
    useClientMappings(ownClient, secondClient);
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    externalSourceAccount(IBAN, secondClientSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));
    when(registrationRepository.markOldOtpsAsConsumed(
            eq(SECOND_CLIENT_ID),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class)))
        .thenReturn(0);

    assertDoesNotThrow(() -> service.quoteTransfer(prepareRequest(IBAN)));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(secondClient));
    ArgumentCaptor<SelfServiceRegistration> registrationCaptor =
        ArgumentCaptor.forClass(SelfServiceRegistration.class);
    verify(registrationRepository).saveAndFlush(registrationCaptor.capture());
    assertEquals(SECOND_CLIENT_ID, registrationCaptor.getValue().getClient().getId());
  }

  @Test
  void confirmTransfer_acceptsOwnedSourceAndContinuesToPinProvider() throws Exception {
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    SelfServiceRegistration registration = validOtpRegistration();
    when(registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                OWN_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(Optional.of(registration));
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));
    when(pinExternalTransferService.getAccountInfo("DEST-IBAN"))
        .thenReturn(
            "{\"holder\":\"Receiver\",\"holderId\":\"1\",\"holderIdType\":1,\"currencyCode\":\"CRC\"}");
    when(pinExternalTransferService.executePinTransfer(any()))
        .thenReturn(
            "{\"operationId\":\"op-1\",\"amount\":10,\"currency\":\"CRC\",\"stateCode\":32}");
    when(feeCollectionService.collectFee(any()))
        .thenReturn(
            FeeCollectionResult.builder()
                .successful(true)
                .status(FeeCollectionResult.Status.SKIPPED)
                .feeAmount(BigDecimal.ZERO)
                .currency("CRC")
                .build());

    assertDoesNotThrow(() -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verify(registration).markConsumed();
    verify(registrationRepository).saveAndFlush(registration);
    verify(pinExternalTransferService).executePinTransfer(any());
    verify(feeCollectionService).collectFee(any());
    verify(applicationEventPublisher).publishEvent(any());
  }

  @Test
  void confirmTransfer_acceptsSourceMappedToSecondClientAndUsesSecondClientDownstream()
      throws Exception {
    useClientMappings(ownClient, secondClient);
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, secondClientSavings);
    SelfServiceRegistration registration = validOtpRegistration();
    when(registrationRepository
            .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
                SECOND_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(Optional.of(registration));
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));
    when(pinExternalTransferService.getAccountInfo("DEST-IBAN"))
        .thenReturn(
            "{\"holder\":\"Receiver\",\"holderId\":\"1\",\"holderIdType\":1,\"currencyCode\":\"CRC\"}");
    when(pinExternalTransferService.executePinTransfer(any()))
        .thenReturn(
            "{\"operationId\":\"op-1\",\"amount\":10,\"currency\":\"CRC\",\"stateCode\":32}");
    when(feeCollectionService.collectFee(any()))
        .thenReturn(
            FeeCollectionResult.builder()
                .successful(true)
                .status(FeeCollectionResult.Status.SKIPPED)
                .feeAmount(BigDecimal.ZERO)
                .currency("CRC")
                .build());

    assertDoesNotThrow(() -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(secondClient));
    verify(registrationRepository)
        .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
            SECOND_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER, OTP);
    verify(registrationRepository, never())
        .findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
            OWN_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER, OTP);
    verify(registration).markConsumed();
    verify(registrationRepository).saveAndFlush(registration);
    verify(secondClientSavings).getWithdrawableBalance();
    verify(pinExternalTransferService).executePinTransfer(any());
    ArgumentCaptor<FeeCollectionRequest> feeRequestCaptor =
        ArgumentCaptor.forClass(FeeCollectionRequest.class);
    verify(feeCollectionService).collectFee(feeRequestCaptor.capture());
    assertEquals(SECOND_CLIENT_ID, feeRequestCaptor.getValue().getClientId());
    assertEquals(SECOND_CLIENT_ID + 1000, feeRequestCaptor.getValue().getFromOfficeId());
    verify(applicationEventPublisher).publishEvent(any());
  }

  @Test
  void confirmTransfer_rejectsForeignSourceBeforeOtpAndFinancialSideEffects() throws Exception {
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, foreignSavings);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verify(foreignSavings, never()).getWithdrawableBalance();
    verifyNoInteractions(
        registrationRepository,
        quoteService,
        pinExternalTransferService,
        sinpeExternalApiClient,
        feeCollectionService,
        accountTransfersWritePlatformService,
        sameBankTransferAuditRepository,
        selfServiceAccountTransferRepository,
        transferAuditRepository,
        applicationEventPublisher);
  }

  @Test
  void confirmTransfer_rejectsForeignExternalIdBeforeOtpValidation() throws Exception {
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    externalSourceAccount("FOREIGN-EXT", foreignSavings);
    AccountTransferConfirmRequest request = confirmRequest("PIN");
    request.setFromAccount("FOREIGN-EXT");

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.confirmTransfer(request, httpRequest));

    verifyNoInteractions(
        registrationRepository,
        quoteService,
        pinExternalTransferService,
        sinpeExternalApiClient,
        feeCollectionService,
        accountTransfersWritePlatformService,
        sameBankTransferAuditRepository,
        selfServiceAccountTransferRepository,
        transferAuditRepository,
        applicationEventPublisher);
  }

  @Test
  void confirmTransfer_rejectsUnsupportedSourceTypeBeforeLoanResolutionOrOtpValidation()
      throws Exception {
    AccountTransferConfirmRequest request = confirmRequest("PIN");
    request.setFromAccountType(1);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.confirmTransfer(request, httpRequest));

    verifyNoInteractions(
        loanAssembler,
        registrationRepository,
        quoteService,
        pinExternalTransferService,
        sinpeExternalApiClient,
        feeCollectionService,
        accountTransfersWritePlatformService,
        applicationEventPublisher);
  }

  @Test
  void resendTransferOtp_acceptsOwnedSourceBeforeOtpLookup() throws Exception {
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    IllegalStateException otpLookupReached = new IllegalStateException("otp lookup reached");
    when(registrationRepository.findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            OWN_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER))
        .thenThrow(otpLookupReached);

    assertThrows(
        IllegalStateException.class, () -> service.resendTransferOtp(resendRequest(), httpRequest));

    verify(registrationRepository)
        .findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            OWN_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER);
    verifyNoInteractions(applicationEventPublisher);
  }

  @Test
  void resendTransferOtp_acceptsSourceMappedToSecondClientAndUsesSecondClientForOtp()
      throws Exception {
    useClientMappings(ownClient, secondClient);
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, secondClientSavings);
    when(registrationRepository.findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            SECOND_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER))
        .thenReturn(List.of());

    assertDoesNotThrow(() -> service.resendTransferOtp(resendRequest(), httpRequest));

    verify(registrationRepository)
        .findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            SECOND_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER);
    verify(registrationRepository, never())
        .findByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
            OWN_CLIENT_ID, SelfServiceRequestType.ACCOUNT_TRANSFER);
    ArgumentCaptor<SelfServiceRegistration> registrationCaptor =
        ArgumentCaptor.forClass(SelfServiceRegistration.class);
    verify(registrationRepository, times(2)).saveAndFlush(registrationCaptor.capture());
    assertEquals(SECOND_CLIENT_ID, registrationCaptor.getAllValues().get(0).getClient().getId());
    assertEquals(SECOND_CLIENT_ID, registrationCaptor.getAllValues().get(1).getClient().getId());
    verify(applicationEventPublisher).publishEvent(any());
  }

  @Test
  void resendTransferOtp_rejectsForeignSourceBeforeOtpInvalidationOrGeneration() throws Exception {
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, foreignSavings);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.resendTransferOtp(resendRequest(), httpRequest));

    verifyNoInteractions(
        registrationRepository, notificationCooldownCache, applicationEventPublisher);
  }

  @Test
  void createTransfer_rejectsValidatorFailureBeforeTransferExecutionOrNotifications()
      throws Exception {
    when(dataValidator.validateCreate(eq("tpt"), eq("{}")))
        .thenThrow(new PlatformApiDataValidationException(List.of()));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.createTransfer("tpt", "{}", httpRequest));

    verify(dataValidator).validateCreate(eq("tpt"), eq("{}"));
    verifyNoInteractions(
        accountTransfersWritePlatformService,
        accountTransfersReadPlatformService,
        tptBeneficiaryReadPlatformService,
        applicationEventPublisher);
  }

  @Test
  void prepareTransfer_acceptsSourceMappedToSecondClient() throws Exception {
    Set<AppSelfServiceUserClientMapping> clientMappings = mappings(ownClient, secondClient);
    when(user.getAppUserClientMappings()).thenReturn(clientMappings);
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, secondClientSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));

    assertDoesNotThrow(() -> service.prepareTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(secondClient));
    verify(secondClientSavings).getWithdrawableBalance();
  }

  @Test
  void prepareTransfer_rejectsSourceOutsideAllMappedClients() throws Exception {
    Set<AppSelfServiceUserClientMapping> clientMappings = mappings(ownClient, secondClient);
    when(user.getAppUserClientMappings()).thenReturn(clientMappings);
    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID, foreignClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, foreignSavings);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.prepareTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verifyNoInteractions(quoteService, registrationRepository, applicationEventPublisher);
  }

  @Test
  void prepareTransfer_returnsSameValidationPatternForMissingAndUnauthorizedSource()
      throws Exception {
    when(savingsAccountAssembler.assembleFrom(SAVINGS_ID, false))
        .thenThrow(new SavingsAccountNotFoundException(SAVINGS_ID));

    PlatformApiDataValidationException missingSource =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.prepareTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    SavingsAccount foreignSavings =
        savingsAccount(SAVINGS_ID + 1, foreignClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID + 1, foreignSavings);

    PlatformApiDataValidationException unauthorizedSource =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.prepareTransfer(prepareRequest(String.valueOf(SAVINGS_ID + 1))));

    assertEquals(
        missingSource.getErrors().get(0).getUserMessageGlobalisationCode(),
        unauthorizedSource.getErrors().get(0).getUserMessageGlobalisationCode());
    assertEquals(
        missingSource.getErrors().get(0).getDefaultUserMessage(),
        unauthorizedSource.getErrors().get(0).getDefaultUserMessage());
    assertEquals("fromAccount", missingSource.getErrors().get(0).getParameterName());
    assertEquals("fromAccount", unauthorizedSource.getErrors().get(0).getParameterName());
    verify(foreignSavings, never()).getWithdrawableBalance();
    verifyNoInteractions(quoteService, registrationRepository, applicationEventPublisher);
  }

  @Test
  void buildTransferDescription_padsGeneratedDescriptionToFastPaymentMinimumLength() {
    AccountTransferPrepareRequest request = new AccountTransferPrepareRequest();

    String description =
        ReflectionTestUtils.invokeMethod(service, "buildTransferDescription", request);

    assertEquals(15, description.length());
    assertTrue(description.startsWith("Transfer via "));
  }

  @Test
  void buildTransferDescription_padsShortDescriptionToFastPaymentMinimumLength() {
    AccountTransferPrepareRequest request = new AccountTransferPrepareRequest();
    request.setTransferDescription("Short");
    request.setTransferMode("PIN");

    String description =
        ReflectionTestUtils.invokeMethod(service, "buildTransferDescription", request);

    assertEquals(15, description.length());
    assertTrue(description.startsWith("Short via PIN"));
    assertRightPaddedOnly(description, "Short via PIN");
  }

  @Test
  void buildTransferDescription_keepsExactlyMinimumLengthDescriptionUnchanged() {
    AccountTransferPrepareRequest request = new AccountTransferPrepareRequest();
    request.setTransferDescription("123456789012345");
    request.setTransferMode("PIN");

    String description =
        ReflectionTestUtils.invokeMethod(service, "buildTransferDescription", request);

    assertEquals("123456789012345", description);
  }

  @Test
  void buildTransferDescription_keepsSupportedOriginalDescriptionWhenAlreadyLongEnough() {
    AccountTransferPrepareRequest request = new AccountTransferPrepareRequest();
    request.setTransferDescription("Original long transfer");
    request.setTransferMode("PIN");

    String description =
        ReflectionTestUtils.invokeMethod(service, "buildTransferDescription", request);

    assertEquals("Original long transfer", description);
  }

  @Test
  void buildTransferDescription_handlesEmptyModeWithOnlyRequiredRightPadding() {
    AccountTransferPrepareRequest request = new AccountTransferPrepareRequest();
    request.setTransferMode("");

    String description =
        ReflectionTestUtils.invokeMethod(service, "buildTransferDescription", request);

    assertEquals(15, description.length());
    assertTrue(description.startsWith("Transfer via "));
    assertRightPaddedOnly(description, "Transfer via ");
  }

  private void assertRightPaddedOnly(String paddedValue, String expectedPrefix) {
    assertTrue(paddedValue.startsWith(expectedPrefix));
    for (int i = expectedPrefix.length(); i < paddedValue.length(); i++) {
      assertEquals(' ', paddedValue.charAt(i));
    }
  }

  private AccountTransferPrepareRequest prepareRequest(String fromAccount) {
    AccountTransferPrepareRequest request = new AccountTransferPrepareRequest();
    request.setFromAccount(fromAccount);
    request.setFromAccountType(2);
    request.setToAccount("DEST-IBAN");
    request.setToAccountType(2);
    request.setTransferAmount(BigDecimal.TEN);
    request.setTransferType("SAME_BANK");
    request.setCurrencyCode("CRC");
    return request;
  }

  private AccountTransferConfirmRequest confirmRequest(String transferType) {
    AccountTransferConfirmRequest request = new AccountTransferConfirmRequest();
    request.setFromAccount(String.valueOf(SAVINGS_ID));
    request.setFromAccountType(2);
    request.setToAccount("DEST-IBAN");
    request.setToAccountType(2);
    request.setTransferAmount(BigDecimal.TEN);
    request.setTransferType(transferType);
    request.setCurrencyCode("CRC");
    request.setOtp(OTP);
    return request;
  }

  private ResendOtpRequest resendRequest() {
    ResendOtpRequest request = new ResendOtpRequest();
    request.setFromAccount(String.valueOf(SAVINGS_ID));
    request.setToAccount("DEST-IBAN");
    request.setTransferType("SAME_BANK");
    return request;
  }

  private void useClientMappings(Client... clients) {
    Set<AppSelfServiceUserClientMapping> clientMappings = mappings(clients);
    when(user.getAppUserClientMappings()).thenReturn(clientMappings);
  }

  private Client client(Long clientId) {
    Client client = mock(Client.class);
    Office office = mock(Office.class);
    lenient().when(client.getId()).thenReturn(clientId);
    lenient().when(client.getOffice()).thenReturn(office);
    lenient().when(client.getDisplayName()).thenReturn("Client " + clientId);
    lenient().when(client.getAccountNumber()).thenReturn("C" + clientId);
    lenient().when(client.getMobileNo()).thenReturn("88888888");
    lenient().when(office.getId()).thenReturn(clientId + 1000);
    return client;
  }

  private SavingsAccount savingsAccount(
      Long accountId, Client client, BigDecimal withdrawableBalance) {
    SavingsAccount savingsAccount = mock(SavingsAccount.class);
    lenient().when(savingsAccount.getId()).thenReturn(accountId);
    lenient().when(savingsAccount.getClient()).thenReturn(client);
    lenient().when(savingsAccount.getWithdrawableBalance()).thenReturn(withdrawableBalance);
    return savingsAccount;
  }

  private void numericSourceAccount(Long accountId, SavingsAccount savingsAccount) {
    when(savingsAccountAssembler.assembleFrom(accountId, false)).thenReturn(savingsAccount);
    when(savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId))
        .thenReturn(savingsAccount);
  }

  private void externalSourceAccount(String identifier, SavingsAccount savingsAccount) {
    ExternalId externalId = ExternalIdFactory.produce(identifier);
    Long accountId = savingsAccount.getId();
    when(externalIdFactory.create(identifier)).thenReturn(externalId);
    when(savingsAccountRepositoryWrapper.findIdByExternalId(externalId)).thenReturn(accountId);
    when(savingsAccountRepositoryWrapper.findOneWithNotFoundDetection(accountId))
        .thenReturn(savingsAccount);
  }

  private SelfServiceRegistration validOtpRegistration() {
    SelfServiceRegistration registration = mock(SelfServiceRegistration.class);
    when(registration.isConsumed()).thenReturn(false);
    when(registration.isExpired(any(LocalDateTime.class))).thenReturn(false);
    return registration;
  }

  private Set<AppSelfServiceUserClientMapping> mappings(Client... clients) {
    return java.util.Arrays.stream(clients)
        .map(
            client -> {
              AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
              when(mapping.getClient()).thenReturn(client);
              return mapping;
            })
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
