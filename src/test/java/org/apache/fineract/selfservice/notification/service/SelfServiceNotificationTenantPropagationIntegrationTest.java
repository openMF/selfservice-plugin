/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.selfservice.external.client.ExternalNotificationSystemClient;
import org.apache.fineract.selfservice.notification.NotificationCooldownCache;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Integration test that verifies tenant context propagation across the {@code afterCommit → @Async
 * → listener} boundary by exercising the real {@link
 * SelfServiceNotificationService#restoreTenantContext} method.
 *
 * <p>Simulates the exact production failure scenario:
 *
 * <ol>
 *   <li>An HTTP request thread captures the tenant into the event before {@code afterCommit}
 *   <li>The auth filter clears {@code ThreadLocal} (afterCommit scenario)
 *   <li>An async executor with a {@code TaskDecorator} dispatches the task
 *   <li>{@code restoreTenantContext} restores the correct tenant from the event
 * </ol>
 */
class SelfServiceNotificationTenantPropagationIntegrationTest {

  private final SelfServiceNotificationService service =
      new SelfServiceNotificationService(
          mock(org.thymeleaf.ITemplateEngine.class),
          mock(MessageSource.class),
          mock(org.apache.fineract.infrastructure.core.service.SelfServicePluginEmailService.class),
          mock(org.apache.fineract.infrastructure.sms.domain.SmsMessageRepository.class),
          mock(
              org.apache.fineract.infrastructure.sms.scheduler.SmsMessageScheduledJobService.class),
          mock(
              org.apache.fineract.infrastructure.campaigns.sms.service
                  .SmsCampaignDropdownReadPlatformService.class),
          mock(NotificationCooldownCache.class),
          mock(Environment.class),
          mock(ExternalNotificationSystemClient.class));

  @AfterEach
  void tearDown() {
    ThreadLocalContextUtil.reset();
  }

  /**
   * Simulates the {@code afterCommit} scenario where the tenant has been cleared from the
   * submitting thread before the async task runs, but the event carries the tenant captured before
   * {@code afterCommit}. Calls the real {@code restoreTenantContext} to guard against regression.
   */
  @Test
  void eventCarriedTenant_IsRestoredOnAsyncWorkerThread() throws InterruptedException {
    FineractPlatformTenant originalTenant =
        new FineractPlatformTenant(1L, "default", "Default Tenant", "UTC", null);
    HashMap<BusinessDateType, LocalDate> originalDates = new HashMap<>();
    originalDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 4, 15));

    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            42L,
            "Alice",
            "Smith",
            "alice",
            "alice@example.com",
            "555-0100",
            true,
            "10.0.0.1",
            Locale.US,
            originalTenant,
            originalDates);

    ThreadLocalContextUtil.reset();

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("test-notif-");
    executor.setTaskDecorator(
        runnable -> {
          FineractPlatformTenant captured = null;
          try {
            captured = ThreadLocalContextUtil.getTenant();
          } catch (IllegalStateException ignored) {
          }
          final FineractPlatformTenant decoratorTenant = captured;
          return () -> {
            try {
              if (decoratorTenant != null) {
                ThreadLocalContextUtil.setTenant(decoratorTenant);
              }
              runnable.run();
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
        });
    executor.initialize();

    AtomicReference<String> workerTenantId = new AtomicReference<>();
    AtomicReference<LocalDate> workerBusinessDate = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try {
      executor.execute(
          () -> {
            try {
              service.restoreTenantContext(event);
              try {
                workerTenantId.set(ThreadLocalContextUtil.getTenant().getTenantIdentifier());
              } catch (IllegalStateException e) {
                workerTenantId.set("NO_TENANT");
              }
              try {
                workerBusinessDate.set(
                    ThreadLocalContextUtil.getBusinessDates().get(BusinessDateType.BUSINESS_DATE));
              } catch (Exception ignored) {
              }
            } finally {
              latch.countDown();
            }
          });

      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertEquals(true, completed, "Async task should complete within timeout");
      assertNotNull(workerTenantId.get(), "Worker thread should have seen a tenant");
      assertEquals(
          "default",
          workerTenantId.get(),
          "Worker thread should see the tenant from the event, not from TaskDecorator (which captured null)");
      assertEquals(
          LocalDate.of(2026, 4, 15),
          workerBusinessDate.get(),
          "Worker thread should see the business dates from the event");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that when both the TaskDecorator and the event carry a tenant, {@code
   * restoreTenantContext} ensures the event-carried tenant takes precedence.
   */
  @Test
  void eventTenant_TakesPrecedenceOverDecoratorTenant() throws InterruptedException {
    FineractPlatformTenant decoratorTenant =
        new FineractPlatformTenant(1L, "decorator-tenant", "Decorator", "UTC", null);
    ThreadLocalContextUtil.setTenant(decoratorTenant);

    FineractPlatformTenant eventTenant =
        new FineractPlatformTenant(2L, "event-tenant", "Event", "UTC", null);
    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.USER_ACTIVATED,
            99L,
            "Bob",
            "Jones",
            "bob",
            "bob@example.com",
            null,
            true,
            null,
            Locale.US,
            eventTenant,
            null);

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setTaskDecorator(
        runnable -> {
          FineractPlatformTenant captured = ThreadLocalContextUtil.getTenant();
          return () -> {
            try {
              if (captured != null) {
                ThreadLocalContextUtil.setTenant(captured);
              }
              runnable.run();
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
        });
    executor.initialize();

    AtomicReference<String> workerTenantId = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try {
      executor.execute(
          () -> {
            try {
              service.restoreTenantContext(event);
              workerTenantId.set(ThreadLocalContextUtil.getTenant().getTenantIdentifier());
            } finally {
              latch.countDown();
            }
          });

      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertEquals(true, completed);
      assertEquals(
          "event-tenant",
          workerTenantId.get(),
          "Event-carried tenant should take precedence over TaskDecorator-captured tenant");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that {@link SelfServiceNotificationEvent#withTenantContext} captures the current
   * thread's tenant, while the plain constructor does not.
   */
  @Test
  void withTenantContext_ProducesEventWithTenantWhilePlainConstructorDoesNot() {
    FineractPlatformTenant tenant =
        new FineractPlatformTenant(5L, "captured-tenant", "Captured", "UTC", null);
    ThreadLocalContextUtil.setTenant(tenant);

    SelfServiceNotificationEvent withContext =
        SelfServiceNotificationEvent.withTenantContext(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "A",
            "B",
            "ab",
            "ab@test.com",
            null,
            true,
            null,
            Locale.US);

    SelfServiceNotificationEvent withoutContext =
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "A",
            "B",
            "ab",
            "ab@test.com",
            null,
            true,
            null,
            Locale.US);

    assertNotNull(withContext.getTenant(), "withTenantContext should capture tenant");
    assertEquals("captured-tenant", withContext.getTenant().getTenantIdentifier());
    assertNull(withoutContext.getTenant(), "Plain constructor should NOT capture tenant");
  }
}
