/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification.listener;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.jms.BytesMessage;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.avro.MessageV1;
import org.apache.fineract.avro.savings.v1.SavingsAccountTransactionDataV1;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountChargeData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SelfServiceSavingsEventNotificationListenerTest {

  // UTC+14 and UTC-11 are 25 hours apart, so the server and the tenant are always on different
  // calendar days, whatever time the test runs.
  private static final String SERVER_TIMEZONE = "Pacific/Kiritimati";
  private static final String TENANT_TIMEZONE = "Pacific/Pago_Pago";
  private static final ZoneId TENANT_ZONE = ZoneId.of(TENANT_TIMEZONE);

  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ClientReadPlatformService clientReadPlatformService;
  @Mock private SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  @Mock private TenantDetailsService tenantDetailsService;
  @Mock private AppUserRepository appUserRepository;
  @Mock private JdbcTemplate jdbcTemplate;

  @InjectMocks private SelfServiceSavingsEventNotificationListener listener;

  private TimeZone serverZone;

  @BeforeEach
  void setUp() {
    serverZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(SERVER_TIMEZONE));
    ReflectionTestUtils.setField(listener, "systemUsername", "mifos");

    when(tenantDetailsService.loadTenantById("default"))
        .thenReturn(new FineractPlatformTenant(1L, "default", "Default", TENANT_TIMEZONE, null));
    when(appUserRepository.findById(1L)).thenReturn(Optional.of(mock(AppUser.class)));
  }

  @AfterEach
  void tearDown() {
    TimeZone.setDefault(serverZone);
    ThreadLocalContextUtil.reset();
    SecurityContextHolder.clearContext();
  }

  @Test
  void onMessage_setsTheTenantsDayAsBusinessDate() throws Exception {
    // The system user lookup runs right after the business date is set, so read it from there
    AtomicReference<LocalDate> businessDate = new AtomicReference<>();
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              businessDate.set(
                  ThreadLocalContextUtil.getBusinessDates().get(BusinessDateType.BUSINESS_DATE));
              return 1L;
            });

    // Same instant in both zones, so this check can't be thrown off by the clock moving on
    Instant now = Instant.now();
    assertNotEquals(
        LocalDate.ofInstant(now, ZoneId.systemDefault()),
        LocalDate.ofInstant(now, TENANT_ZONE),
        "server and tenant should be on different days");

    LocalDate tenantDayBefore = LocalDate.now(TENANT_ZONE);
    listener.onMessage(bytesMessage(envelope("SomeOtherBusinessEvent", ByteBuffer.allocate(0))));
    LocalDate tenantDayAfter = LocalDate.now(TENANT_ZONE);

    assertTenantDayBetween(tenantDayBefore, tenantDayAfter, businessDate.get());
  }

  @Test
  void onMessage_usesTheTenantsDay_whenTheTransactionHasNoDate() throws Exception {
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(1L);
    when(savingsAccountReadPlatformService.retrieveOne(5L)).thenReturn(savingsAccountOfClient(7L));
    when(clientReadPlatformService.retrieveOne(7L))
        .thenReturn(ClientData.lookup(7L, "Ana Mora", null, null));

    SavingsAccountTransactionDataV1 txn = new SavingsAccountTransactionDataV1();
    txn.setId(10L);
    txn.setAccountId(5L);
    txn.setAccountNo("000000005");
    txn.setAmount(new BigDecimal("100.00"));
    LocalDate tenantDayBefore = LocalDate.now(TENANT_ZONE);
    listener.onMessage(bytesMessage(envelope("SavingsDepositBusinessEvent", txn.toByteBuffer())));
    LocalDate tenantDayAfter = LocalDate.now(TENANT_ZONE);

    ArgumentCaptor<SelfServiceNotificationEvent> event =
        ArgumentCaptor.forClass(SelfServiceNotificationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertTenantDayBetween(
        tenantDayBefore,
        tenantDayAfter,
        LocalDate.parse((String) event.getValue().getContextData().get("transactionDate")));
  }

  /**
   * The tenant's day can roll over while the listener runs, so accept either side of midnight. The
   * server's day is always at least one day after both, so the old code still fails.
   */
  private static void assertTenantDayBetween(LocalDate before, LocalDate after, LocalDate actual) {
    assertTrue(
        !actual.isBefore(before) && !actual.isAfter(after),
        "expected the tenant's day (" + before + " to " + after + ") but was " + actual);
  }

  private static SavingsAccountData savingsAccountOfClient(Long clientId) {
    return SavingsAccountData.importInstanceIndividual(
        clientId,
        5L,
        null,
        LocalDate.of(2026, 1, 1),
        BigDecimal.ONE,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        List.<SavingsAccountChargeData>of(),
        false,
        null,
        null,
        null);
  }

  private static MessageV1 envelope(String type, ByteBuffer data) {
    MessageV1 message = new MessageV1();
    message.setId(1L);
    message.setSource("test");
    message.setType(type);
    message.setCategory("SAVINGS");
    message.setCreatedAt("2026-11-06T00:00:00");
    message.setBusinessDate("2026-11-06");
    message.setTenantId("default");
    message.setIdempotencyKey("key-1");
    message.setDataschema("test");
    message.setData(data);
    return message;
  }

  private static BytesMessage bytesMessage(MessageV1 message) throws Exception {
    ByteBuffer buffer = message.toByteBuffer();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    BytesMessage jmsMessage = mock(BytesMessage.class);
    when(jmsMessage.getBodyLength()).thenReturn((long) bytes.length);
    when(jmsMessage.readBytes(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              byte[] target = invocation.getArgument(0);
              System.arraycopy(bytes, 0, target, 0, bytes.length);
              return bytes.length;
            });
    return jmsMessage;
  }
}
