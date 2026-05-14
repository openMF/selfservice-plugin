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
package org.apache.fineract.selfservice.notification;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import lombok.Getter;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.context.ApplicationEvent;

/**
 * Immutable event carrying all data needed for self-service notification delivery, including the
 * originating Fineract tenant context so that async listeners can restore multi-tenant database
 * routing on their own thread.
 */
@Getter
public class SelfServiceNotificationEvent extends ApplicationEvent {

  public enum Type {
    USER_ACTIVATED("user-activated"),
    LOGIN_SUCCESS("login-success"),
    LOGIN_FAILURE("login-failure");

    private final String templatePrefix;

    Type(String templatePrefix) {
      this.templatePrefix = templatePrefix;
    }

    public String getTemplatePrefix() {
      return templatePrefix;
    }
  }

  private final Type type;
  private final Long userId;
  private final String firstName;
  private final String lastName;
  private final String username;
  private final String email;
  private final String mobileNumber;
  private final boolean emailMode;
  private final String ipAddress;
  private final Locale locale;

  /**
   * The Fineract tenant that was active when the event was created. Used by async listeners to
   * restore multi-tenant database routing. May be {@code null} if no tenant was available at
   * creation time.
   */
  private final FineractPlatformTenant tenant;

  /**
   * The business dates that were active when the event was created. Used by async listeners to
   * restore date context on the worker thread. May be {@code null} if no business dates were
   * available at creation time.
   */
  private final HashMap<BusinessDateType, LocalDate> businessDates;

  /**
   * Creates a new self-service notification event.
   *
   * @param source the object on which the event initially occurred (never {@code null})
   * @param type the notification event type (never {@code null})
   * @param userId the user identifier (never {@code null})
   * @param firstName user's first name (may be {@code null})
   * @param lastName user's last name (may be {@code null})
   * @param username user's login username (may be {@code null})
   * @param email user's email address (may be {@code null})
   * @param mobileNumber user's mobile number (may be {@code null})
   * @param emailMode {@code true} for email delivery, {@code false} for SMS
   * @param ipAddress the originating IP address (may be {@code null})
   * @param locale the locale for notification content (may be {@code null})
   */
  public SelfServiceNotificationEvent(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale) {
    this(
        source,
        type,
        userId,
        firstName,
        lastName,
        username,
        email,
        mobileNumber,
        emailMode,
        ipAddress,
        locale,
        null,
        null);
  }

  /**
   * Creates a new self-service notification event with explicit tenant context.
   *
   * @param source the object on which the event initially occurred (never {@code null})
   * @param type the notification event type (never {@code null})
   * @param userId the user identifier (never {@code null})
   * @param firstName user's first name (may be {@code null})
   * @param lastName user's last name (may be {@code null})
   * @param username user's login username (may be {@code null})
   * @param email user's email address (may be {@code null})
   * @param mobileNumber user's mobile number (may be {@code null})
   * @param emailMode {@code true} for email delivery, {@code false} for SMS
   * @param ipAddress the originating IP address (may be {@code null})
   * @param locale the locale for notification content (may be {@code null})
   * @param tenant the Fineract tenant active at event creation time (may be {@code null})
   * @param businessDates the business dates active at event creation time (may be {@code null})
   */
  public SelfServiceNotificationEvent(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale,
      FineractPlatformTenant tenant,
      HashMap<BusinessDateType, LocalDate> businessDates) {
    super(source);
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.userId = Objects.requireNonNull(userId, "userId must not be null");
    this.firstName = firstName;
    this.lastName = lastName;
    this.username = username;
    this.email = email;
    this.mobileNumber = mobileNumber;
    this.emailMode = emailMode;
    this.ipAddress = ipAddress;
    this.locale = locale;
    this.tenant = tenant;
    this.businessDates = businessDates != null ? new HashMap<>(businessDates) : null;
  }

  /**
   * Factory method that creates an event and automatically captures the current thread's Fineract
   * tenant context and business dates. Use this from request-processing threads where the tenant
   * context is still available.
   *
   * <p>This is the <strong>preferred</strong> way to create events from synchronous call sites
   * (e.g. REST controllers, service methods). For events published from {@code
   * TransactionSynchronization.afterCommit()} callbacks where the tenant may already be cleared,
   * capture the tenant <em>before</em> registering the synchronization and pass it to the full
   * constructor.
   *
   * @see #SelfServiceNotificationEvent(Object, Type, Long, String, String, String, String, String,
   *     boolean, String, Locale, FineractPlatformTenant, HashMap)
   */
  public static SelfServiceNotificationEvent withTenantContext(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale) {
    FineractPlatformTenant currentTenant = null;
    HashMap<BusinessDateType, LocalDate> currentDates = null;
    try {
      currentTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    try {
      currentDates = ThreadLocalContextUtil.getBusinessDates();
    } catch (IllegalArgumentException ignored) {
    }
    return new SelfServiceNotificationEvent(
        source,
        type,
        userId,
        firstName,
        lastName,
        username,
        email,
        mobileNumber,
        emailMode,
        ipAddress,
        locale,
        currentTenant,
        currentDates);
  }

  /**
   * Returns a safe, non-PII string representation suitable for logging. Sensitive fields (email,
   * username, mobileNumber, ipAddress) are excluded.
   */
  @Override
  public String toString() {
    return "SelfServiceNotificationEvent[type="
        + type
        + ", userId="
        + userId
        + ", emailMode="
        + emailMode
        + ", locale="
        + locale
        + ", tenant="
        + (tenant != null ? tenant.getTenantIdentifier() : "null")
        + "]";
  }
}
