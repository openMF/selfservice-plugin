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
import static org.mockito.ArgumentMatchers.contains;
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
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.selfservice.account.data.SelfAccountTemplateData;
import java.util.function.Supplier;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.TransactionDateManagementService;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.LegalForm;
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
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferApproval;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferApprovalRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAuditRepository;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistration;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRegistrationRepository;
import org.apache.fineract.selfservice.registration.domain.SelfServiceRequestType;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.apache.fineract.selfservice.stepup.service.StepUpPayloadHasher;
import org.apache.fineract.selfservice.stepup.service.StepUpTransferPayloads;
import org.apache.fineract.selfservice.stepup.service.StepUpGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Mock private NotificationDeliveryModeUtil notificationDeliveryModeUtil;
  @Mock private StepUpGuard stepUpGuard;
  @Mock private SelfServiceTransferApprovalRepository transferApprovalRepository;
  @Mock private AppSelfServiceUserRepository appSelfServiceUserRepository;
  @Mock private TransactionTemplate transactionTemplate;
  @Mock private PlatformTransactionManager transactionManager;

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
    lenient().when(notificationDeliveryModeUtil.determineMode(any(), any())).thenReturn(true);
    lenient().when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
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
            eq("selfuser"),
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
            eq("selfuser"),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class)))
        .thenReturn(0);

    assertDoesNotThrow(() -> service.quoteTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(secondClient));
    verify(registrationRepository)
        .markOldOtpsAsConsumed(
            eq(SECOND_CLIENT_ID),
            eq("selfuser"),
            eq(SelfServiceRequestType.ACCOUNT_TRANSFER),
            any(LocalDateTime.class));
    verify(registrationRepository, never())
        .markOldOtpsAsConsumed(
            eq(OWN_CLIENT_ID),
            eq("selfuser"),
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
            eq("selfuser"),
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
    when(registrationRepository.findUserOtps(
            OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(List.of(registration));
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

  /**
   * MX-503: once the gateway accepted the transfer, an answer that is not JSON must not fail the
   * confirm, or the rollback would free the consumed OTP for a second send.
   */
  @Test
  void confirmTransfer_pinGatewayAcceptsWithNonJsonAnswer_completesAndKeepsTheOtpConsumed()
      throws Exception {
    SelfServiceRegistration registration = ownSourceWithValidOtp();
    when(pinExternalTransferService.getAccountInfo("DEST-IBAN"))
        .thenReturn(
            "{\"holder\":\"Receiver\",\"holderId\":\"1\",\"holderIdType\":1,\"currencyCode\":\"CRC\"}");
    when(pinExternalTransferService.executePinTransfer(any())).thenReturn("OK");

    Object result = service.confirmTransfer(confirmRequest("PIN"), httpRequest);

    assertEquals("PIN", ((Map<?, ?>) result).get("transferType"));
    Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) result).get("data");
    assertEquals(0, BigDecimal.TEN.compareTo((BigDecimal) data.get("debitedAmount")));
    verify(registration).markConsumed();
    verify(pinExternalTransferService).executePinTransfer(any());
    verify(applicationEventPublisher).publishEvent(any());
  }

  @Test
  void confirmTransfer_sinpeGatewayAcceptsWithNonJsonAnswer_completesAndKeepsTheOtpConsumed()
      throws Exception {
    SelfServiceRegistration registration = ownSourceWithValidOtp();
    when(sinpeExternalApiClient.transferToPhone(any())).thenReturn("<html>OK</html>");

    Object result = service.confirmTransfer(confirmRequest("SINPE_MOVIL"), httpRequest);

    assertEquals("SINPE_MOVIL", ((Map<?, ?>) result).get("transferType"));
    verify(registration).markConsumed();
    verify(sinpeExternalApiClient).transferToPhone(any());
    verify(applicationEventPublisher).publishEvent(any());
  }

  /** The caller owns the source account, the legacy OTP is valid and no fee is charged. */
  private SelfServiceRegistration ownSourceWithValidOtp() {
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    SelfServiceRegistration registration = validOtpRegistration();
    when(registrationRepository.findUserOtps(
            OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(List.of(registration));
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));
    when(feeCollectionService.collectFee(any()))
        .thenReturn(
            FeeCollectionResult.builder()
                .successful(true)
                .status(FeeCollectionResult.Status.SKIPPED)
                .feeAmount(BigDecimal.ZERO)
                .currency("CRC")
                .build());
    return registration;
  }

  @Test
  void confirmTransfer_acceptsSourceMappedToSecondClientAndUsesSecondClientDownstream()
      throws Exception {
    useClientMappings(ownClient, secondClient);
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, secondClientSavings);
    SelfServiceRegistration registration = validOtpRegistration();
    when(registrationRepository.findUserOtps(
            SECOND_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(List.of(registration));
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
        .findUserOtps(SECOND_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP);
    verify(registrationRepository, never())
        .findUserOtps(OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP);
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
    when(registrationRepository
            .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
                OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER))
        .thenThrow(otpLookupReached);

    assertThrows(
        IllegalStateException.class, () -> service.resendTransferOtp(resendRequest(), httpRequest));

    verify(registrationRepository)
        .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
            OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER);
    verifyNoInteractions(applicationEventPublisher);
  }

  @Test
  void resendTransferOtp_acceptsSourceMappedToSecondClientAndUsesSecondClientForOtp()
      throws Exception {
    useClientMappings(ownClient, secondClient);
    SavingsAccount secondClientSavings =
        savingsAccount(SAVINGS_ID, secondClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, secondClientSavings);
    when(registrationRepository
            .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
                SECOND_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER))
        .thenReturn(List.of());

    assertDoesNotThrow(() -> service.resendTransferOtp(resendRequest(), httpRequest));

    verify(registrationRepository)
        .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
            SECOND_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER);
    verify(registrationRepository, never())
        .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
            OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER);
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

  // ===================================================================================
  // MX-488: step-up (OTP) enforcement
  // ===================================================================================

  private void stepUpProtectsConfirmation(boolean protectedByStepUp) {
    lenient()
        .when(stepUpGuard.isProtected(StepUpAction.CONFIRM_TRANSFER))
        .thenReturn(protectedByStepUp);
  }

  @Test
  void quoteTransfer_withStepUpProtectingConfirm_doesNotSendTheLegacyOtp() throws Exception {
    stepUpProtectsConfirmation(true);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));

    Object response =
        assertDoesNotThrow(() -> service.quoteTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(quoteService).calculateFee(any(AccountTransferPrepareRequest.class), eq(ownClient));
    assertTrue(response.toString().contains("feeAmount"));
    verifyNoInteractions(registrationRepository, applicationEventPublisher);
  }

  @Test
  void quoteTransfer_withoutStepUp_stillSendsTheLegacyOtp() throws Exception {
    stepUpProtectsConfirmation(false);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(BigDecimal.ZERO, BigDecimal.TEN, "CRC", "fee"));

    assertDoesNotThrow(() -> service.quoteTransfer(prepareRequest(String.valueOf(SAVINGS_ID))));

    verify(registrationRepository).saveAndFlush(any(SelfServiceRegistration.class));
    verify(applicationEventPublisher).publishEvent(any());
  }

  @Test
  void confirmTransfer_withStepUp_rejectsAMissingTokenBeforeAnyMoneyMovesOrOtpIsChecked()
      throws Exception {
    stepUpProtectsConfirmation(true);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    org.mockito.Mockito.doThrow(StepUpException.required(StepUpAction.CONFIRM_TRANSFER))
        .when(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.CONFIRM_TRANSFER), any(), any());

    StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    assertEquals("error.msg.selfservice.stepup.required", ex.getErrorCode());
    verifyNoInteractions(
        registrationRepository,
        pinExternalTransferService,
        sinpeExternalApiClient,
        feeCollectionService,
        accountTransfersWritePlatformService,
        applicationEventPublisher);
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmTransfer_withStepUp_bindsTheTokenToTheExactTransferBeingConfirmed() throws Exception {
    stepUpProtectsConfirmation(true);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    org.mockito.Mockito.doThrow(StepUpException.invalid())
        .when(stepUpGuard)
        .requireIfProtected(any(), any(), any());

    assertThrows(
        StepUpException.class, () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    ArgumentCaptor<Supplier<String>> hash = ArgumentCaptor.forClass(Supplier.class);
    verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.CONFIRM_TRANSFER), eq(httpRequest), hash.capture());
    assertEquals(
        StepUpPayloadHasher.hash(
            StepUpAction.CONFIRM_TRANSFER,
            StepUpTransferPayloads.forConfirm(
                String.valueOf(SAVINGS_ID), 2, "DEST-IBAN", 2, null, "PIN", "CRC", BigDecimal.TEN)),
        hash.getValue().get());
  }

  @Test
  void confirmTransfer_withStepUp_afterTheGuardPasses_executesWithoutTheLegacyOtp()
      throws Exception {
    stepUpProtectsConfirmation(true);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
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

    verify(stepUpGuard).requireIfProtected(eq(StepUpAction.CONFIRM_TRANSFER), any(), any());
    verify(pinExternalTransferService).executePinTransfer(any());
    // the legacy in-line OTP is not consulted at all
    verifyNoInteractions(registrationRepository);
  }

  @Test
  void resendTransferOtp_withStepUp_isReplacedByTheStepUpEndpoint() {
    stepUpProtectsConfirmation(true);

    StepUpException ex =
        assertThrows(
            StepUpException.class,
            () -> service.resendTransferOtp(new ResendOtpRequest(), httpRequest));

    assertEquals("error.msg.selfservice.stepup.legacy.otp.disabled", ex.getErrorCode());
    verifyNoInteractions(registrationRepository, applicationEventPublisher);
  }

  private Map<String, Object> transferParams(Long fromId, Long toId, String amount) {
    Map<String, Object> params = new HashMap<>();
    params.put("fromAccount", new SelfAccountTemplateData(fromId, 2, 5L, 1L));
    params.put("toAccount", new SelfAccountTemplateData(toId, 2, 6L, 1L));
    params.put("transactionAmount", new BigDecimal(amount));
    return params;
  }

  @Test
  @SuppressWarnings("unchecked")
  void createTransfer_tpt_requiresStepUpBoundToTheValidatedTransfer_beforeCreatingIt() {
    when(dataValidator.validateCreate(eq("tpt"), anyString()))
        .thenReturn(transferParams(1L, 2L, "100.00"));
    numericSourceAccount(1L, savingsAccount(1L, ownClient, BigDecimal.TEN));
    org.mockito.Mockito.doThrow(StepUpException.required(StepUpAction.TPT_TRANSFER))
        .when(stepUpGuard)
        .requireIfProtected(any(), any(), any());

    assertThrows(StepUpException.class, () -> service.createTransfer("tpt", "{}", httpRequest));

    ArgumentCaptor<Supplier<String>> hash = ArgumentCaptor.forClass(Supplier.class);
    verify(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.TPT_TRANSFER), eq(httpRequest), hash.capture());
    assertEquals(
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 2L, 2, new BigDecimal("100"))),
        hash.getValue().get());
    verifyNoInteractions(accountTransfersWritePlatformService, applicationEventPublisher);
  }

  @Test
  void createTransfer_betweenOwnAccounts_usesTheOwnTransferAction() {
    when(dataValidator.validateCreate(eq(""), anyString())).thenReturn(transferParams(1L, 3L, "5"));
    numericSourceAccount(1L, savingsAccount(1L, ownClient, BigDecimal.TEN));
    org.mockito.Mockito.doThrow(StepUpException.invalid())
        .when(stepUpGuard)
        .requireIfProtected(any(), any(), any());

    assertThrows(StepUpException.class, () -> service.createTransfer("", "{}", httpRequest));

    verify(stepUpGuard).requireIfProtected(eq(StepUpAction.OWN_TRANSFER), any(), any());
    verifyNoInteractions(accountTransfersWritePlatformService);
  }

  @Test
  void createTransfer_notificationCarriesAccountNumbersClientNamesAndDescription() {
    when(dataValidator.validateCreate(eq(""), anyString()))
        .thenReturn(transferParams(1L, 2L, "100.00"));
    numericSourceAccount(1L, savingsAccount(1L, ownClient, BigDecimal.TEN));
    when(fromApiJsonHelper.extractStringNamed(eq("transferDescription"), any()))
        .thenReturn("Rent");
    CommandProcessingResult result = mock(CommandProcessingResult.class);
    when(result.getResourceId()).thenReturn(77L);
    when(accountTransfersWritePlatformService.create(any())).thenReturn(result);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1L)))
        .thenReturn(List.of("000000001"));
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(2L)))
        .thenReturn(List.of("000000002"));
    when(clientReadPlatformService.retrieveOne(5L))
        .thenReturn(ClientData.lookup(5L, "Ana Mora", null, null));
    when(clientReadPlatformService.retrieveOne(6L))
        .thenReturn(ClientData.lookup(6L, "Luis Vega", null, null));

    service.createTransfer("", "{\"transferDescription\":\"Rent\"}", httpRequest);

    ArgumentCaptor<SelfServiceNotificationEvent> eventCaptor =
        ArgumentCaptor.forClass(SelfServiceNotificationEvent.class);
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    Map<String, Object> contextData = eventCaptor.getValue().getContextData();
    assertEquals("000000001", contextData.get("fromAccountNumber"));
    assertEquals("000000002", contextData.get("toAccountNumber"));
    assertEquals("Ana Mora", contextData.get("fromClientName"));
    assertEquals("Luis Vega", contextData.get("toClientName"));
    assertEquals("Rent", contextData.get("transferDescription"));
    assertEquals("77", contextData.get("transferId"));
  }

  @Test
  void createTransfer_unresolvedAccountNumber_showsNaNotInternalId() {
    when(dataValidator.validateCreate(eq(""), anyString()))
        .thenReturn(transferParams(1L, 2L, "100.00"));
    numericSourceAccount(1L, savingsAccount(1L, ownClient, BigDecimal.TEN));
    when(accountTransfersWritePlatformService.create(any()))
        .thenReturn(mock(CommandProcessingResult.class));
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());

    service.createTransfer("", "{}", httpRequest);

    ArgumentCaptor<SelfServiceNotificationEvent> eventCaptor =
        ArgumentCaptor.forClass(SelfServiceNotificationEvent.class);
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    Map<String, Object> contextData = eventCaptor.getValue().getContextData();
    assertEquals("N/A", contextData.get("fromAccountNumber"));
    assertEquals("N/A", contextData.get("toAccountNumber"));
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

  // ===================================================================================
  // MX-489: user-scoped legacy OTPs
  // ===================================================================================

  @Test
  void confirmTransfer_legacyOtp_isLookedUpForTheCallerOnly_soAnotherUsersOtpIsRejected()
      throws Exception {
    stepUpProtectsConfirmation(false);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    // An OTP for the same client issued to "otheruser" is invisible to "selfuser".
    SelfServiceRegistration othersOtp = validOtpRegistration();
    when(registrationRepository.findUserOtps(
            OWN_CLIENT_ID, "otheruser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(List.of(othersOtp));

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verify(registrationRepository)
        .findUserOtps(OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP);
    verifyNoInteractions(pinExternalTransferService, transferApprovalRepository);
  }

  @Test
  void confirmTransfer_legacyOtp_rejectsExpiredAndAlreadyUsedOtps() throws Exception {
    stepUpProtectsConfirmation(false);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    SelfServiceRegistration expired = mock(SelfServiceRegistration.class);
    when(expired.isExpired(any(LocalDateTime.class))).thenReturn(true);
    SelfServiceRegistration used = mock(SelfServiceRegistration.class);
    when(used.isConsumed()).thenReturn(true);

    for (SelfServiceRegistration otp : List.of(expired, used)) {
      when(registrationRepository.findUserOtps(
              OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
          .thenReturn(List.of(otp));
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));
      verify(otp, never()).markConsumed();
    }
    verifyNoInteractions(pinExternalTransferService);
  }

  @Test
  void resendTransferOtp_expiresOnlyTheCallersOtps() throws Exception {
    stepUpProtectsConfirmation(false);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);
    SelfServiceRegistration mine = mock(SelfServiceRegistration.class);
    when(registrationRepository
            .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
                OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER))
        .thenReturn(List.of(mine));

    assertDoesNotThrow(() -> service.resendTransferOtp(resendRequest(), httpRequest));

    verify(mine).markConsumed();
    verify(registrationRepository, never())
        .findByClient_IdAndUsernameAndRequestTypeAndConsumedFalseOrderByIdDesc(
            eq(OWN_CLIENT_ID), eq("otheruser"), any());
  }

  // ===================================================================================
  // MX-489: client-level threshold and holding the transfer
  // ===================================================================================

  private static final Long APPROVAL_ID = 5L;
  private static final Long INITIATOR_ID = 77L;

  /** Makes the own client an ENTITY whose CRC account has the given threshold (null = none). */
  private SavingsAccount entitySourceAccount(String threshold) {
    when(ownClient.getLegalForm()).thenReturn(LegalForm.ENTITY.getValue());
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("1000.00"));
    MonetaryCurrency crc = mock(MonetaryCurrency.class);
    lenient().when(crc.getCode()).thenReturn("CRC");
    lenient().when(ownSavings.getCurrency()).thenReturn(crc);
    numericSourceAccount(SAVINGS_ID, ownSavings);
    lenient()
        .when(
            jdbcTemplate.queryForList(
                contains("m_selfservice_client_approval_threshold"),
                eq(BigDecimal.class),
                eq(OWN_CLIENT_ID),
                eq("CRC")))
        .thenReturn(threshold == null ? List.of() : List.of(new BigDecimal(threshold)));
    tenantDualApproval(List.of("true"));
    return ownSavings;
  }

  /** Values of the tenant's SELF_SERVICE_DUAL_APPROVAL.enabled property (empty = no row). */
  private void tenantDualApproval(List<String> enabledValues) {
    lenient()
        .when(jdbcTemplate.queryForList(contains("SELF_SERVICE_DUAL_APPROVAL"), eq(String.class)))
        .thenReturn(enabledValues);
  }

  private void approverOtpProtected(boolean isProtected) {
    lenient()
        .when(stepUpGuard.isProtected(StepUpAction.APPROVE_SECOND_SIGNATURE))
        .thenReturn(isProtected);
  }

  /**
   * The PIN channel's first external call; the PIN path rewraps its failure, so tests assert that
   * execution was reached by verifying this call rather than by the exception type.
   */
  private void verifyExecutionReached() throws Exception {
    verify(pinExternalTransferService).getAccountInfo("DEST-IBAN");
  }

  private void validLegacyOtp() {
    stepUpProtectsConfirmation(false);
    SelfServiceRegistration otp = validOtpRegistration();
    when(registrationRepository.findUserOtps(
            OWN_CLIENT_ID, "selfuser", SelfServiceRequestType.ACCOUNT_TRANSFER, OTP))
        .thenReturn(List.of(otp));
  }

  @Test
  void confirmTransfer_entityBelowThreshold_executesImmediately() throws Exception {
    validLegacyOtp();
    approverOtpProtected(true);
    entitySourceAccount("10.01");

    assertThrows(
        RuntimeException.class, () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verifyExecutionReached();
    verifyNoInteractions(transferApprovalRepository);
  }

  @Test
  void confirmTransfer_entityWithoutThresholdForTheCurrency_executesImmediately() throws Exception {
    validLegacyOtp();
    entitySourceAccount(null);

    assertThrows(
        RuntimeException.class, () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verifyExecutionReached();
    verifyNoInteractions(transferApprovalRepository);
  }

  @Test
  void confirmTransfer_entityAtThreshold_tenantSwitchOff_executesImmediately() throws Exception {
    validLegacyOtp();
    approverOtpProtected(true);
    entitySourceAccount("10.00");
    tenantDualApproval(List.of("false"));

    assertThrows(
        RuntimeException.class, () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verifyExecutionReached();
    verify(jdbcTemplate, never())
        .queryForList(contains("approval_threshold"), eq(BigDecimal.class), any(), any());
    verifyNoInteractions(transferApprovalRepository);
  }

  @Test
  void confirmTransfer_entityAtThreshold_tenantSwitchMissing_executesImmediately()
      throws Exception {
    validLegacyOtp();
    approverOtpProtected(true);
    entitySourceAccount("10.00");
    tenantDualApproval(List.of());

    assertThrows(
        RuntimeException.class, () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verifyExecutionReached();
    verifyNoInteractions(transferApprovalRepository);
  }

  @Test
  void confirmTransfer_personClient_neverReadsThresholds() throws Exception {
    validLegacyOtp();
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);

    assertThrows(
        RuntimeException.class, () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    verifyExecutionReached();
    verify(jdbcTemplate, never())
        .queryForList(contains("approval_threshold"), eq(BigDecimal.class), any(), any());
    verifyNoInteractions(transferApprovalRepository);
  }

  @Test
  void confirmTransfer_entityAtThreshold_isHeldWithoutMovingMoney() throws Exception {
    validLegacyOtp();
    approverOtpProtected(true);
    entitySourceAccount("10.00");
    when(transferApprovalRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Object response = service.confirmTransfer(confirmRequest("PIN"), httpRequest);

    assertTrue(response.toString().contains("PENDING_APPROVAL"));
    ArgumentCaptor<SelfServiceTransferApproval> held =
        ArgumentCaptor.forClass(SelfServiceTransferApproval.class);
    verify(transferApprovalRepository).saveAndFlush(held.capture());
    assertEquals(OWN_CLIENT_ID, held.getValue().getClientId());
    assertEquals(USER_ID, held.getValue().getRequestedBy());
    assertEquals(SelfServiceTransferApproval.Status.PENDING, held.getValue().getStatus());
    assertEquals(0, BigDecimal.TEN.compareTo(held.getValue().getTransferAmount()));
    assertEquals("CRC", held.getValue().getCurrencyCode());
    assertFalse(held.getValue().getRequestJson().contains(OTP), "the OTP must not be stored");
    verifyNoInteractions(
        pinExternalTransferService,
        sinpeExternalApiClient,
        feeCollectionService,
        accountTransfersWritePlatformService);
  }

  @Test
  void confirmTransfer_entityAtThresholdWithoutFee_isRefusedWhenTheQuotedFeeExceedsTheBalance() {
    validLegacyOtp();
    approverOtpProtected(true);
    entitySourceAccount("10.00");
    when(quoteService.calculateFee(any(), any(Client.class)))
        .thenReturn(
            new AccountTransferQuoteResponse(
                new BigDecimal("995.00"), new BigDecimal("1005.00"), "CRC", "fee"));

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    assertTrue(errorCodes(ex).contains("insufficient.account.balance"));
    verify(transferApprovalRepository, never()).saveAndFlush(any());
    verifyNoInteractions(pinExternalTransferService);
  }

  @Test
  void confirmTransfer_thresholdUsesTheAccountCurrency_notTheRequestedOne() throws Exception {
    validLegacyOtp();
    approverOtpProtected(true);
    entitySourceAccount("10.00");
    when(transferApprovalRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AccountTransferConfirmRequest request = confirmRequest("PIN");
    request.setCurrencyCode("USD");

    Object response = service.confirmTransfer(request, httpRequest);

    assertTrue(response.toString().contains("PENDING_APPROVAL"));
  }

  @Test
  void confirmTransfer_heldTransfer_failsClosedWhenTheApproverOtpIsNotConfigured()
      throws Exception {
    validLegacyOtp();
    approverOtpProtected(false);
    entitySourceAccount("10.00");

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.confirmTransfer(confirmRequest("PIN"), httpRequest));

    assertTrue(errorCodes(ex).contains("otp.not.configured"));
    verifyNoInteractions(transferApprovalRepository, pinExternalTransferService);
  }

  @Test
  void createTransfer_legacyPath_refusesATransferThatNeedsASecondSignature() {
    entitySourceAccount("50");
    when(dataValidator.validateCreate(eq(""), anyString()))
        .thenReturn(transferParams(SAVINGS_ID, 3L, "50"));

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.createTransfer("", "{}", httpRequest));

    assertTrue(errorCodes(ex).contains("dual.approval.required"));
    verifyNoInteractions(accountTransfersWritePlatformService, stepUpGuard);
  }

  // ===================================================================================
  // MX-489: second signature
  // ===================================================================================

  private SelfServiceTransferApproval pendingApproval(Long requestedBy) {
    return approval(requestedBy, SelfServiceTransferApproval.Status.PENDING);
  }

  private SelfServiceTransferApproval approval(
      Long requestedBy, SelfServiceTransferApproval.Status status) {
    AccountTransferConfirmRequest request = confirmRequest("PIN");
    request.setOtp(null);
    return SelfServiceTransferApproval.builder()
        .id(APPROVAL_ID)
        .clientId(OWN_CLIENT_ID)
        .requestedBy(requestedBy)
        .status(status)
        .transferAmount(BigDecimal.TEN)
        .currencyCode("CRC")
        .requestJson(new com.google.gson.Gson().toJson(request))
        .createdAt(TEST_NOW)
        .build();
  }

  private void approvalExists(SelfServiceTransferApproval approval) {
    when(transferApprovalRepository.findById(approval.getId())).thenReturn(Optional.of(approval));
  }

  private void callerIsActiveSignatory(boolean isSignatory) {
    lenient()
        .when(
            jdbcTemplate.queryForObject(
                contains("m_selfservice_entity_signatory"),
                eq(Integer.class),
                eq(OWN_CLIENT_ID),
                eq(USER_ID),
                eq(true)))
        .thenReturn(isSignatory ? 1 : 0);
  }

  private AppSelfServiceUser initiator(boolean enabled) {
    AppSelfServiceUser initiator = mock(AppSelfServiceUser.class);
    lenient().when(initiator.getId()).thenReturn(INITIATOR_ID);
    lenient().when(initiator.isEnabled()).thenReturn(enabled);
    Set<AppSelfServiceUserClientMapping> initiatorMappings = mappings(ownClient);
    lenient().when(initiator.getAppUserClientMappings()).thenReturn(initiatorMappings);
    when(appSelfServiceUserRepository.findById(INITIATOR_ID)).thenReturn(Optional.of(initiator));
    return initiator;
  }

  /** The initiator is active and still owns the source account. */
  private void initiatorCanStillTransfer() {
    initiator(true);
    numericSourceAccount(
        SAVINGS_ID, savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00")));
  }

  private void decisionWins(boolean wins) {
    lenient()
        .when(transferApprovalRepository.decide(eq(APPROVAL_ID), any(), any(), any(), any()))
        .thenReturn(wins ? 1 : 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void approveTransfer_bySignatory_consumesAnOtpBoundToThisApproval_andExecutesAsTheInitiator()
      throws Exception {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    decisionWins(true);
    AppSelfServiceUser initiator = initiator(true);
    SavingsAccount ownSavings = savingsAccount(SAVINGS_ID, ownClient, new BigDecimal("100.00"));
    numericSourceAccount(SAVINGS_ID, ownSavings);

    assertThrows(RuntimeException.class, () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    verifyExecutionReached();

    ArgumentCaptor<Supplier<String>> hash = ArgumentCaptor.forClass(Supplier.class);
    verify(stepUpGuard)
        .requireIfProtected(
            eq(StepUpAction.APPROVE_SECOND_SIGNATURE), eq(httpRequest), hash.capture());
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.APPROVE_SECOND_SIGNATURE, Map.of("approvalId", "5")),
        hash.getValue().get());
    assertFalse(
        StepUpPayloadHasher.hash(StepUpAction.APPROVE_SECOND_SIGNATURE, Map.of("approvalId", "6"))
            .equals(hash.getValue().get()),
        "an OTP verified for another approval must not match");
    verify(transferApprovalRepository)
        .decide(
            eq(APPROVAL_ID),
            eq(SelfServiceTransferApproval.Status.PENDING),
            eq(SelfServiceTransferApproval.Status.APPROVED),
            eq(USER_ID),
            any());
    // Checked before the claim and again when the transfer executes.
    verify(initiator, times(2)).validateHasCreatePermission("ACCOUNTTRANSFER");
  }

  @Test
  void approveTransfer_withAnInvalidOrForeignOtp_neverDecidesOrMovesMoney() {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    initiatorCanStillTransfer();
    org.mockito.Mockito.doThrow(StepUpException.invalid())
        .when(stepUpGuard)
        .requireIfProtected(eq(StepUpAction.APPROVE_SECOND_SIGNATURE), any(), any());

    assertThrows(StepUpException.class, () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    verify(transferApprovalRepository, never()).decide(any(), any(), any(), any(), any());
    verifyNoInteractions(pinExternalTransferService);
  }

  @Test
  void approveTransfer_byNonOrInactiveSignatory_looksLikeAMissingApproval() {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(false);

    PlatformApiDataValidationException foreign =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));
    PlatformApiDataValidationException missing =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(999L, httpRequest));

    assertEquals(errorCodes(missing), errorCodes(foreign));
    assertTrue(errorCodes(foreign).contains("not.found"));
    verifyNoInteractions(stepUpGuard, pinExternalTransferService);
    verify(transferApprovalRepository, never()).decide(any(), any(), any(), any(), any());
  }

  @Test
  void approveTransfer_byTheInitiator_isRefusedEvenIfTheyAreASignatory() {
    approverOtpProtected(true);
    approvalExists(pendingApproval(USER_ID));
    callerIsActiveSignatory(true);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("initiator.cannot.decide"));
    verifyNoInteractions(stepUpGuard, pinExternalTransferService);
  }

  @Test
  void approveTransfer_alreadyDecided_isRefused() {
    approverOtpProtected(true);
    approvalExists(approval(INITIATOR_ID, SelfServiceTransferApproval.Status.APPROVED));
    callerIsActiveSignatory(true);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("already.decided"));
    verifyNoInteractions(stepUpGuard, pinExternalTransferService);
  }

  @Test
  void approveTransfer_losingAConcurrentDecision_neverExecutes() {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    initiatorCanStillTransfer();
    decisionWins(false);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("already.decided"));
    verifyNoInteractions(pinExternalTransferService);
    verify(transferApprovalRepository, never())
        .decide(any(), any(), eq(SelfServiceTransferApproval.Status.FAILED), any(), any());
  }

  @Test
  void approveTransfer_failsClosedWhenTheApproverOtpIsNotConfigured() {
    approverOtpProtected(false);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("otp.not.configured"));
    verify(transferApprovalRepository, never()).decide(any(), any(), any(), any(), any());
  }

  @Test
  void approveTransfer_whenTheInitiatorIsDisabled_isRefusedBeforeTheApprovalOrOtpIsUsed() {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    decisionWins(true);
    initiator(false);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("initiator.inactive"));
    verifyNoInteractions(pinExternalTransferService);
    verify(stepUpGuard, never()).requireIfProtected(any(), any(), any());
    verify(transferApprovalRepository, never()).decide(any(), any(), any(), any(), any());
  }

  @Test
  void approveTransfer_whenFundsDroppedSinceConfirm_isRefusedBeforeTheApprovalOrOtpIsUsed() {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    decisionWins(true);
    initiator(true);
    numericSourceAccount(SAVINGS_ID, savingsAccount(SAVINGS_ID, ownClient, BigDecimal.ONE));

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("insufficient.account.balance"));
    verifyNoInteractions(pinExternalTransferService);
    verify(stepUpGuard, never()).requireIfProtected(any(), any(), any());
    verify(transferApprovalRepository, never()).decide(any(), any(), any(), any(), any());
  }

  @Test
  void approveTransfer_commitsTheApprovalBeforeMoneyMoves_andAFailedExecutionEndsAsFailed()
      throws Exception {
    approverOtpProtected(true);
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    decisionWins(true);
    initiatorCanStillTransfer();
    when(transferApprovalRepository.decide(
            eq(APPROVAL_ID),
            eq(SelfServiceTransferApproval.Status.APPROVED),
            eq(SelfServiceTransferApproval.Status.FAILED),
            any(),
            any()))
        .thenReturn(1);
    when(pinExternalTransferService.getAccountInfo("DEST-IBAN"))
        .thenThrow(new IllegalStateException("gateway timeout"));

    assertThrows(RuntimeException.class, () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(
            transferApprovalRepository, transactionManager, pinExternalTransferService);
    order
        .verify(transferApprovalRepository)
        .decide(
            eq(APPROVAL_ID),
            eq(SelfServiceTransferApproval.Status.PENDING),
            eq(SelfServiceTransferApproval.Status.APPROVED),
            eq(USER_ID),
            any());
    order.verify(transactionManager).commit(any());
    order.verify(pinExternalTransferService).getAccountInfo("DEST-IBAN");
    order
        .verify(transferApprovalRepository)
        .decide(
            eq(APPROVAL_ID),
            eq(SelfServiceTransferApproval.Status.APPROVED),
            eq(SelfServiceTransferApproval.Status.FAILED),
            eq(USER_ID),
            any());
    verify(transferApprovalRepository, never())
        .decide(any(), any(), eq(SelfServiceTransferApproval.Status.PENDING), any(), any());
  }

  @Test
  void approveTransfer_ofAFailedApproval_isRefused_soItCanNeverBeSentTwice() {
    approverOtpProtected(true);
    approvalExists(approval(INITIATOR_ID, SelfServiceTransferApproval.Status.FAILED));
    callerIsActiveSignatory(true);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> service.approveTransfer(APPROVAL_ID, httpRequest));

    assertTrue(errorCodes(ex).contains("already.decided"));
    verifyNoInteractions(stepUpGuard, pinExternalTransferService);
  }

  @Test
  void rejectTransfer_bySignatory_closesItWithoutMovingMoney() {
    approvalExists(pendingApproval(INITIATOR_ID));
    callerIsActiveSignatory(true);
    decisionWins(true);

    Object response = service.rejectTransfer(APPROVAL_ID);

    assertTrue(response.toString().contains("REJECTED"));
    verify(transferApprovalRepository)
        .decide(
            eq(APPROVAL_ID),
            eq(SelfServiceTransferApproval.Status.PENDING),
            eq(SelfServiceTransferApproval.Status.REJECTED),
            eq(USER_ID),
            any());
    verifyNoInteractions(pinExternalTransferService, accountTransfersWritePlatformService);
  }

  @Test
  void rejectTransfer_byTheInitiator_isRefused() {
    approvalExists(pendingApproval(USER_ID));
    callerIsActiveSignatory(true);

    assertThrows(
        PlatformApiDataValidationException.class, () -> service.rejectTransfer(APPROVAL_ID));
    verify(transferApprovalRepository, never()).decide(any(), any(), any(), any(), any());
  }

  @Test
  void retrievePendingApprovals_showsOnlyTheCallersSignatoryClients() {
    when(jdbcTemplate.queryForList(
            contains("m_selfservice_entity_signatory"), eq(Long.class), eq(USER_ID), eq(true)))
        .thenReturn(List.of(OWN_CLIENT_ID));
    when(transferApprovalRepository.findByClientIdInAndStatusOrderByIdAsc(
            List.of(OWN_CLIENT_ID), SelfServiceTransferApproval.Status.PENDING))
        .thenReturn(List.of(pendingApproval(INITIATOR_ID), pendingApproval(USER_ID)));

    String json = (String) service.retrievePendingApprovals();

    assertTrue(json.contains("\"canDecide\":true"));
    assertTrue(json.contains("\"canDecide\":false"));
    assertFalse(json.contains(OTP));
  }

  @Test
  void retrievePendingApprovals_forANonSignatory_isEmpty() {
    assertEquals("[]", service.retrievePendingApprovals());
    verifyNoInteractions(transferApprovalRepository);
  }

  private static String errorCodes(PlatformApiDataValidationException ex) {
    return ex.getErrors().stream()
        .map(error -> error.getUserMessageGlobalisationCode())
        .collect(Collectors.joining(","));
  }
}
