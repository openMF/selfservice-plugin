package org.apache.fineract.selfservice.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.selfservice.notification.starter.SelfServiceNotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(SelfServiceNotificationAsyncIntegrationTest.AsyncTestConfig.class)
public class SelfServiceNotificationAsyncIntegrationTest {

  @Autowired private ApplicationEventPublisher applicationEventPublisher;

  @Autowired private AsyncTestConfig.TestNotificationListener testListener;

  @BeforeEach
  void setupContext() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
    HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
    businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 1, 2));
    businessDates.put(BusinessDateType.COB_DATE, LocalDate.of(2026, 1, 1));
    ThreadLocalContextUtil.setBusinessDates(businessDates);
    testListener.reset();
  }

  @Test
  void testLoginFiresAsyncNotification() throws InterruptedException {
    long start = System.currentTimeMillis();

    applicationEventPublisher.publishEvent(
        new SelfServiceNotificationEvent(
            this,
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            1L,
            "Test",
            "User",
            "test-user",
            "test@example.com",
            null,
            true,
            "127.0.0.1",
            Locale.US));

    long publishDuration = System.currentTimeMillis() - start;
    assertTrue(
        publishDuration < 500,
        "Notification publishing should return before listener work completes");

    assertTrue(
        testListener.awaitStarted(),
        "Notification event was not asynchronously received within timeout limit");

    String threadName = testListener.getThreadName().get();
    assertTrue(
        threadName != null && threadName.startsWith("notif-"),
        "Listener did not execute on async thread pool. Expected notif-*, got: " + threadName);

    testListener.release();
    assertTrue(
        testListener.awaitCompleted(),
        "Async notification listener did not complete within timeout limit");
  }

  @TestConfiguration
  @Import(SelfServiceNotificationConfig.class)
  public static class AsyncTestConfig {

    @Bean
    public TestNotificationListener testNotificationListener() {
      return new TestNotificationListener();
    }

    public static class TestNotificationListener {

      private CountDownLatch started;
      private CountDownLatch release;
      private CountDownLatch completed;
      private AtomicReference<String> threadName;

      public void reset() {
        this.started = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        this.completed = new CountDownLatch(1);
        this.threadName = new AtomicReference<>();
      }

      @Async("notificationExecutor")
      @EventListener
      public void onNotification(SelfServiceNotificationEvent event) throws InterruptedException {
        if (event.getType() == SelfServiceNotificationEvent.Type.LOGIN_SUCCESS) {
          this.threadName.set(Thread.currentThread().getName());
          this.started.countDown();
          this.release.await(5, TimeUnit.SECONDS);
          this.completed.countDown();
        }
      }

      public boolean awaitStarted() throws InterruptedException {
        return this.started.await(5, TimeUnit.SECONDS);
      }

      public void release() {
        this.release.countDown();
      }

      public boolean awaitCompleted() throws InterruptedException {
        return this.completed.await(5, TimeUnit.SECONDS);
      }

      public AtomicReference<String> getThreadName() {
        return this.threadName;
      }
    }
  }
}
