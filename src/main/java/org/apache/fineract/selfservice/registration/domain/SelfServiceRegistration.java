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
        null);
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
    this.createdDate = DateUtils.getLocalDateTimeOfSystem();
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
        expiresAt);
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

  public boolean isExpired(LocalDateTime now) {
    if (now == null) {
      throw new IllegalArgumentException("now must not be null");
    }
    return this.expiresAt != null && !now.isBefore(this.expiresAt);
  }

  public void markConsumed() {
    this.consumed = true;
  }
}
