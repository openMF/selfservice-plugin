/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.client.domain.Client;

/**
 * Audit / OTP request entity for self-service registration, enrollment, transfers, and SINPE.
 *
 * <p><b>Date/time policy (multi-tenant):</b> timestamps are taken from the tenant clock via {@link
 * DateUtils#getLocalDateTimeOfTenant()}, the same source used by {@code
 * TransactionDateUtil#getCurrentTenantLocalDateTime()}. Callers that already resolved a tenant
 * {@link LocalDateTime} (e.g. services injecting {@code TransactionDateUtil}) should prefer the
 * overloads that accept an explicit {@code now} / {@code createdAt} so the value is not resolved
 * twice.
 */
@Entity
@Table(name = "request_audit_table")
public class SelfServiceRegistration extends AbstractPersistableCustom<Long> {

  public static final String PASSWORD_RESET_SENTINEL = "<PASSWORD_RESET>";

  @ManyToOne
  @JoinColumn(name = "client_id", nullable = false)
  private Client client;

  @Column(name = "account_number", length = 100, nullable = false)
  private String accountNumber;

  @Column(name = "firstname", length = 100, nullable = false)
  private String firstName;

  @Column(name = "middlename", length = 100, nullable = true)
  private String middleName;

  @Column(name = "lastname", length = 100, nullable = false)
  private String lastName;

  @Column(name = "mobile_number", length = 50, nullable = true)
  private String mobileNumber;

  @Column(name = "email", length = 100, nullable = false)
  private String email;

  @Column(name = "authentication_token", length = 100, nullable = true)
  private String authenticationToken;

  @Column(name = "external_authorization_token", length = 100, nullable = true)
  private String externalAuthorizationToken;

  @Column(name = "username", length = 100, nullable = false)
  private String username;

  @Column(name = "password", length = 250, nullable = false)
  private String password;

  @Column(name = "created_date", nullable = false)
  private LocalDateTime createdDate;

  @Column(name = "expires_at", nullable = true)
  private LocalDateTime expiresAt;

  @Column(name = "consumed", nullable = false)
  private boolean consumed;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_type", length = 30, nullable = false)
  private SelfServiceRequestType requestType;

  @Version
  @Column(name = "version")
  private Long version;

  @Column(name = "notification_dispatched_at", nullable = true)
  private LocalDateTime notificationDispatchedAt;

  @Column(name = "notification_dispatch_error", length = 500, nullable = true)
  private String notificationDispatchError;

  public SelfServiceRegistration() {}

  public SelfServiceRegistration(
      final Client client,
      String accountNumber,
      final String firstName,
      final String middleName,
      final String lastName,
      final String mobileNumber,
      final String email,
      final String authenticationToken,
      final String username,
      final String password) {
    this(
        client,
        accountNumber,
        firstName,
        middleName,
        lastName,
        mobileNumber,
        email,
        authenticationToken,
        authenticationToken,
        username,
        password,
        SelfServiceRequestType.REGISTRATION,
        null,
        tenantNow());
  }

  public SelfServiceRegistration(
      final Client client,
      String accountNumber,
      final String firstName,
      final String middleName,
      final String lastName,
      final String mobileNumber,
      final String email,
      final String authenticationToken,
      final String externalAuthorizationToken,
      final String username,
      final String password,
      final SelfServiceRequestType requestType,
      final LocalDateTime expiresAt) {
    this(
        client,
        accountNumber,
        firstName,
        middleName,
        lastName,
        mobileNumber,
        email,
        authenticationToken,
        externalAuthorizationToken,
        username,
        password,
        requestType,
        expiresAt,
        tenantNow());
  }

  /**
   * Full constructor allowing the service layer to supply a tenant-aware {@code createdAt} from
   * {@code TransactionDateUtil#getCurrentTenantLocalDateTime()}.
   */
  public SelfServiceRegistration(
      final Client client,
      String accountNumber,
      final String firstName,
      final String middleName,
      final String lastName,
      final String mobileNumber,
      final String email,
      final String authenticationToken,
      final String externalAuthorizationToken,
      final String username,
      final String password,
      final SelfServiceRequestType requestType,
      final LocalDateTime expiresAt,
      final LocalDateTime createdAt) {
    this.client = client;
    this.accountNumber = accountNumber;
    this.firstName = firstName;
    this.middleName = middleName;
    this.lastName = lastName;
    this.mobileNumber = mobileNumber;
    this.email = email;
    this.authenticationToken = authenticationToken;
    this.externalAuthorizationToken = externalAuthorizationToken;
    this.username = username;
    this.password = password;
    this.createdDate = createdAt != null ? createdAt : tenantNow();
    this.expiresAt = expiresAt;
    this.consumed = false;
    this.requestType = requestType;
  }

  public static SelfServiceRegistration instance(
      final Client client,
      final String accountNumber,
      final String firstName,
      final String middleName,
      final String lastName,
      final String mobileNumber,
      final String email,
      final String authenticationToken,
      final String username,
      final String password) {
    return new SelfServiceRegistration(
        client,
        accountNumber,
        firstName,
        middleName,
        lastName,
        mobileNumber,
        email,
        authenticationToken,
        username,
        password);
  }

  public static SelfServiceRegistration instance(
      final Client client,
      final String accountNumber,
      final String firstName,
      final String middleName,
      final String lastName,
      final String mobileNumber,
      final String email,
      final String authenticationToken,
      final String externalAuthenticationToken,
      final String username,
      final String password,
      final SelfServiceRequestType requestType,
      final LocalDateTime expiresAt) {
    return new SelfServiceRegistration(
        client,
        accountNumber,
        firstName,
        middleName,
        lastName,
        mobileNumber,
        email,
        authenticationToken,
        externalAuthenticationToken,
        username,
        password,
        requestType,
        expiresAt,
        tenantNow());
  }

  /**
   * Preferred factory when the service already holds a tenant {@link LocalDateTime} from {@code
   * TransactionDateUtil}.
   */
  public static SelfServiceRegistration instance(
      final Client client,
      final String accountNumber,
      final String firstName,
      final String middleName,
      final String lastName,
      final String mobileNumber,
      final String email,
      final String authenticationToken,
      final String externalAuthenticationToken,
      final String username,
      final String password,
      final SelfServiceRequestType requestType,
      final LocalDateTime expiresAt,
      final LocalDateTime createdAt) {
    return new SelfServiceRegistration(
        client,
        accountNumber,
        firstName,
        middleName,
        lastName,
        mobileNumber,
        email,
        authenticationToken,
        externalAuthenticationToken,
        username,
        password,
        requestType,
        expiresAt,
        createdAt);
  }

  /**
   * Tenant-aware "now". Delegates to {@link DateUtils#getLocalDateTimeOfTenant()} — the same clock
   * used by {@code TransactionDateUtil#getCurrentTenantLocalDateTime()}. Must not use system/JVM
   * local time.
   */
  private static LocalDateTime tenantNow() {
    return DateUtils.getLocalDateTimeOfTenant();
  }

  /** Marks notification as dispatched using the tenant clock. */
  public void markDispatched() {
    this.notificationDispatchedAt = tenantNow();
  }

  /**
   * Marks notification as dispatched using an explicit tenant timestamp (preferred when the caller
   * already resolved time via {@code TransactionDateUtil}).
   */
  public void markDispatched(LocalDateTime dispatchedAt) {
    this.notificationDispatchedAt = dispatchedAt != null ? dispatchedAt : tenantNow();
  }

  public void markDispatchFailed(String error) {
    this.notificationDispatchError = error;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getNotificationDispatchError() {
    return notificationDispatchError;
  }

  public void setNotificationDispatchError(String notificationDispatchError) {
    this.notificationDispatchError = notificationDispatchError;
  }

  public LocalDateTime getNotificationDispatchedAt() {
    return notificationDispatchedAt;
  }

  public void setNotificationDispatchedAt(LocalDateTime notificationDispatchedAt) {
    this.notificationDispatchedAt = notificationDispatchedAt;
  }

  public Client getClient() {
    return this.client;
  }

  public String getFirstName() {
    return this.firstName;
  }

  public String getMiddleName() {
    return this.middleName;
  }

  public String getLastName() {
    return this.lastName;
  }

  public String getMobileNumber() {
    return this.mobileNumber;
  }

  public String getEmail() {
    return this.email;
  }

  public String getAuthenticationToken() {
    return this.authenticationToken;
  }

  /**
   * @deprecated use {@link #getExternalAuthorizationToken()} to match the persisted field name
   */
  @Deprecated(forRemoval = false)
  public String getExternalAuthenticationToken() {
    return getExternalAuthorizationToken();
  }

  public String getExternalAuthorizationToken() {
    return this.externalAuthorizationToken;
  }

  public LocalDateTime getCreatedDate() {
    return this.createdDate;
  }

  public String getUsername() {
    return this.username;
  }

  public String getPassword() {
    return this.password;
  }

  public String getAccountNumber() {
    return this.accountNumber;
  }

  public SelfServiceRequestType getRequestType() {
    return this.requestType;
  }

  public LocalDateTime getExpiresAt() {
    return this.expiresAt;
  }

  public boolean isConsumed() {
    return this.consumed;
  }

  /**
   * @param now tenant-aware current time (e.g. from {@code
   *     TransactionDateUtil#getCurrentTenantLocalDateTime()}); must not be null
   */
  public boolean isExpired(LocalDateTime now) {
    if (now == null) {
      throw new IllegalArgumentException("now must not be null");
    }
    return this.getExpiresAt() != null && !now.isBefore(this.expiresAt);
  }

  /**
   * Convenience expiry check using the tenant clock. Prefer {@link #isExpired(LocalDateTime)} when
   * the service layer already holds a {@code TransactionDateUtil} timestamp.
   */
  public boolean isExpired() {
    return isExpired(tenantNow());
  }

  public void markConsumed() {
    this.consumed = true;
  }
}