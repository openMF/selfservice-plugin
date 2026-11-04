/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.selfservice.account.data.FeeCollectionRequest;
import org.apache.fineract.selfservice.account.domain.SelfServiceAccountForFeesRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAudit;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferAuditRepository;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfServiceFeeCollectionServiceImplTest {

  // UTC+14, far from any usual server zone, so the server clock and the tenant clock always differ
  // by hours.
  private static final String TENANT_TIMEZONE = "Pacific/Kiritimati";
  private static final Long CLIENT_ID = 5L;

  @Mock private SelfServiceAccountForFeesRepository externalServicePropertiesRepository;
  @Mock private SelfServiceTransferFeeRepository feeRepository;
  @Mock private SelfServiceTransferAuditRepository transferAuditRepository;

  private SelfServiceFeeCollectionServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new SelfServiceFeeCollectionServiceImpl(
            externalServicePropertiesRepository,
            feeRepository,
            null, // bccrExchangeRateService
            transferAuditRepository,
            null, // accountTransfersWritePlatformService
            null, // savingsAccountRepositoryWrapper
            null, // externalIdFactory
            null, // savingsAccountAssembler
            null, // loanAssembler
            null, // fromApiJsonHelper
            null); // jdbcTemplate
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", TENANT_TIMEZONE, null));
  }

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  @Test
  void collectFee_auditRowIsStampedWithTheTenantClock() {
    when(externalServicePropertiesRepository.getProperties("SELF_SERVICE_FEE_CONFIG"))
        .thenReturn(Map.of("transfer_fee_enabled", "true"));
    SelfServiceTransferFee fee = new SelfServiceTransferFee();
    fee.setTransferType("SINPE_MOVIL");
    fee.setFeeType("FIXED");
    fee.setFeeValue(new BigDecimal("150"));
    fee.setFeeCurrency("CRC");
    fee.setThresholdPeriod("DAILY");
    fee.setThresholdAmount(new BigDecimal("100000"));
    when(feeRepository.findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
            "SINPE_MOVIL", "CRC", "INSTANT"))
        .thenReturn(Optional.of(fee));
    when(transferAuditRepository.getDailySinpeMovilTotal(eq(CLIENT_ID), any()))
        .thenReturn(BigDecimal.ZERO);

    // Under the daily threshold, so the fee is waived and the transfer is audited as COMPLETED.
    service.collectFee(
        FeeCollectionRequest.builder()
            .transferType("SINPE_MOVIL")
            .currencyCode("CRC")
            .transferAmount(new BigDecimal("5000"))
            .clientId(CLIENT_ID)
            .build());

    ArgumentCaptor<LocalDate> queriedDay = ArgumentCaptor.forClass(LocalDate.class);
    verify(transferAuditRepository).getDailySinpeMovilTotal(eq(CLIENT_ID), queriedDay.capture());
    ArgumentCaptor<SelfServiceTransferAudit> audit =
        ArgumentCaptor.forClass(SelfServiceTransferAudit.class);
    verify(transferAuditRepository).saveAndFlush(audit.capture());

    LocalDateTime stored = audit.getValue().getProcessingDate().toLocalDateTime();
    LocalDateTime tenantNow = LocalDateTime.now(ZoneId.of(TENANT_TIMEZONE));
    assertTrue(
        Duration.between(stored, tenantNow).abs().toMinutes() < 1,
        "processingDate " + stored + " should be the tenant's wall clock, about " + tenantNow);
    // The day the row lands on is the day the daily total query counts.
    assertEquals(queriedDay.getValue(), stored.toLocalDate());
  }
}
