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
package org.apache.fineract.selfservice.security.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.constants.TwoFactorConstants;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.client.service.SelfServiceClientReadPlatformService;
import org.apache.fineract.selfservice.notification.NotificationContext;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserData;
import org.apache.fineract.selfservice.security.exception.SelfServiceDisabledException;
import org.apache.fineract.selfservice.security.exception.SelfServiceLockedException;
import org.apache.fineract.selfservice.security.exception.SelfServicePasswordResetRequiredException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.data.AppSelfServiceUserData;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.apache.fineract.useradministration.data.RoleData;
import org.apache.fineract.useradministration.domain.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty("fineract.security.basicauth.enabled")
@Path("/v1/self/authentication")
@Tag(
    name = "Authentication HTTP Basic",
    description =
        "An API capability that allows client applications to verify authentication details using HTTP Basic Authentication.")
@RequiredArgsConstructor
public class SelfAuthenticationApiResource {

  @Value("${fineract.security.2fa.enabled}")
  private boolean twoFactorEnabled;

  public static class AuthenticateRequest {

    public String username;
    public String password;
  }

  @Qualifier("selfServiceAuthenticationProvider")
  private final DaoAuthenticationProvider customAuthenticationProvider;

  private final ToApiJsonSerializer<AppSelfServiceUserData> apiJsonSerializerService;
  private final PlatformSelfServiceSecurityContext springSecurityPlatformSecurityContext;
  private final SelfServiceClientReadPlatformService clientReadPlatformService;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final Environment env;
  private final org.apache.fineract.selfservice.useradministration.domain
          .AppSelfServiceUserRepository
      appUserRepository;

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Verify authentication",
      description =
          "Authenticates the credentials provided and returns the set roles and permissions allowed.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfAuthenticationApiResourceSwagger.PostSelfAuthenticationResponse
                              .class)))
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfAuthenticationApiResourceSwagger.PostSelfAuthenticationResponse
                              .class)))
  @ApiResponse(responseCode = "400", description = "Unauthenticated. Please login")
  @ApiResponse(responseCode = "403", description = "Password reset required")
  public String authenticate(
      @Parameter(hidden = true) final String apiRequestBodyAsJson,
      @QueryParam("returnClientList") @DefaultValue("true") boolean returnClientList,
      @Context HttpServletRequest httpRequest) {
    // TODO FINERACT-819: sort out Jersey so JSON conversion does not have
    // to be done explicitly via GSON here, but implicit by arg
    AuthenticateRequest request =
        new Gson().fromJson(apiRequestBodyAsJson, AuthenticateRequest.class);
    if (request == null) {
      throw new IllegalArgumentException(
          "Invalid JSON in BODY (no longer URL param; see FINERACT-726) of POST to /authentication: "
              + apiRequestBodyAsJson);
    }
    if (StringUtils.isBlank(request.username) || StringUtils.isBlank(request.password)) {
      throw new IllegalArgumentException(
          "Username or Password is blank in JSON (see FINERACT-726) of POST to /authentication.");
    }

    final Authentication authentication =
        new UsernamePasswordAuthenticationToken(request.username, request.password);

    Authentication authenticationCheck = null;
    try {
      authenticationCheck = this.customAuthenticationProvider.authenticate(authentication);
    } catch (SelfServiceDisabledException ex) {
      AppSelfServiceUser failedUser = ex.getUser();
      String mobileNumber = extractMobile(failedUser);
      boolean emailMode = determineMode(failedUser.getEmail(), mobileNumber);

      try (NotificationContext.Scope ignored =
          NotificationContext.bind(SelfServiceNotificationEvent.Type.LOGIN_FAILURE.name())) {
        try {
          applicationEventPublisher.publishEvent(
              SelfServiceNotificationEvent.withTenantContext(
                  this,
                  SelfServiceNotificationEvent.Type.LOGIN_FAILURE,
                  failedUser.getId(),
                  failedUser.getFirstname(),
                  failedUser.getLastname(),
                  request.username,
                  failedUser.getEmail(),
                  mobileNumber,
                  emailMode,
                  extractClientIp(httpRequest),
                  httpRequest.getLocale()));
        } catch (Exception publishEx) {
          log.warn("Failed to publish login failure notification", publishEx);
        }
      }
      throw ex;
    } catch (SelfServiceLockedException ex) {
      AppSelfServiceUser failedUser = ex.getUser();
      String mobileNumber = extractMobile(failedUser);
      boolean emailMode = determineMode(failedUser.getEmail(), mobileNumber);

      try (NotificationContext.Scope ignored =
          NotificationContext.bind(SelfServiceNotificationEvent.Type.LOGIN_FAILURE.name())) {
        try {
          applicationEventPublisher.publishEvent(
              SelfServiceNotificationEvent.withTenantContext(
                  this,
                  SelfServiceNotificationEvent.Type.LOGIN_FAILURE,
                  failedUser.getId(),
                  failedUser.getFirstname(),
                  failedUser.getLastname(),
                  request.username,
                  failedUser.getEmail(),
                  mobileNumber,
                  emailMode,
                  extractClientIp(httpRequest),
                  httpRequest.getLocale()));
        } catch (Exception publishEx) {
          log.warn("Failed to publish login failure notification", publishEx);
        }
      }
      throw ex;
    } catch (BadCredentialsException ex) {
      AppSelfServiceUser failedUser =
          this.appUserRepository.findAppSelfServiceUserByName(request.username);
      if (failedUser != null) {
        String mobileNumber = extractMobile(failedUser);
        boolean emailMode = determineMode(failedUser.getEmail(), mobileNumber);
        try (NotificationContext.Scope ignored =
            NotificationContext.bind(SelfServiceNotificationEvent.Type.LOGIN_FAILURE.name())) {
          try {
            applicationEventPublisher.publishEvent(
                SelfServiceNotificationEvent.withTenantContext(
                    this,
                    SelfServiceNotificationEvent.Type.LOGIN_FAILURE,
                    failedUser.getId(),
                    failedUser.getFirstname(),
                    failedUser.getLastname(),
                    request.username,
                    failedUser.getEmail(),
                    mobileNumber,
                    emailMode,
                    extractClientIp(httpRequest),
                    httpRequest.getLocale()));
          } catch (Exception publishEx) {
            log.warn("Failed to publish login failure notification", publishEx);
          }
        }
      }
      throw ex;
    }

    final Collection<String> permissions = new ArrayList<>();
    SelfServiceAuthenticatedUserData authenticatedUserData =
        new SelfServiceAuthenticatedUserData()
            .setUsername(request.username)
            .setPermissions(permissions);

    if (authenticationCheck != null && authenticationCheck.isAuthenticated()) {
      final Collection<GrantedAuthority> authorities =
          new ArrayList<>(authenticationCheck.getAuthorities());
      for (final GrantedAuthority grantedAuthority : authorities) {
        permissions.add(grantedAuthority.getAuthority());
      }

      final byte[] base64EncodedAuthenticationKey =
          Base64.getEncoder()
              .encode((request.username + ":" + request.password).getBytes(StandardCharsets.UTF_8));

      final AppSelfServiceUser principal = (AppSelfServiceUser) authenticationCheck.getPrincipal();
      final Collection<RoleData> roles = new ArrayList<>();
      final Set<Role> userRoles = principal.getRoles();
      for (final Role role : userRoles) {
        roles.add(role.toData());
      }

      final Long officeId = principal.getOffice().getId();
      final String officeName = principal.getOffice().getName();

      final Long staffId = principal.getStaffId();
      final String staffDisplayName = principal.getStaffDisplayName();

      final EnumOptionData organisationalRole = principal.organisationalRoleData();

      boolean isTwoFactorRequired =
          this.twoFactorEnabled
              && !principal.hasSpecificPermissionTo(
                  TwoFactorConstants.BYPASS_TWO_FACTOR_PERMISSION);
      Long userId = principal.getId();
      if (this.springSecurityPlatformSecurityContext.doesPasswordHasToBeRenewed(principal)) {
        authenticatedUserData =
            new SelfServiceAuthenticatedUserData()
                .setUsername(request.username)
                .setUserId(userId)
                .setBase64EncodedAuthenticationKey(
                    new String(base64EncodedAuthenticationKey, StandardCharsets.UTF_8))
                .setAuthenticated(true)
                .setShouldRenewPassword(true)
                .setTwoFactorAuthenticationRequired(isTwoFactorRequired);
        throw new SelfServicePasswordResetRequiredException(authenticatedUserData);
      } else {
        String mobileNumber = extractMobile(principal);
        boolean emailMode = determineMode(principal.getEmail(), mobileNumber);
        try (NotificationContext.Scope ignored =
            NotificationContext.bind(SelfServiceNotificationEvent.Type.LOGIN_SUCCESS.name())) {
          try {
            applicationEventPublisher.publishEvent(
                SelfServiceNotificationEvent.withTenantContext(
                    this,
                    SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
                    principal.getId(),
                    principal.getFirstname(),
                    principal.getLastname(),
                    request.username,
                    principal.getEmail(),
                    mobileNumber,
                    emailMode,
                    extractClientIp(httpRequest),
                    httpRequest.getLocale()));
          } catch (Exception e) {
            log.warn("Failed to publish login success notification", e);
          }
        }

        authenticatedUserData =
            new SelfServiceAuthenticatedUserData()
                .setUsername(request.username)
                .setOfficeId(officeId)
                .setOfficeName(officeName)
                .setStaffId(staffId)
                .setStaffDisplayName(staffDisplayName)
                .setOrganisationalRole(organisationalRole)
                .setRoles(roles)
                .setPermissions(permissions)
                .setUserId(principal.getId())
                .setAuthenticated(true)
                .setBase64EncodedAuthenticationKey(
                    new String(base64EncodedAuthenticationKey, StandardCharsets.UTF_8))
                .setTwoFactorAuthenticationRequired(isTwoFactorRequired)
                .setClients(
                    returnClientList
                        ? clientReadPlatformService.retrieveSelfServiceUserClients(userId)
                        : null);
      }
    }

    return this.apiJsonSerializerService.serialize(authenticatedUserData);
  }

  private String extractClientIp(HttpServletRequest httpRequest) {
    if (httpRequest == null) {
      return null;
    }
    String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
    if (StringUtils.isNotBlank(xForwardedFor)) {
      String firstToken = xForwardedFor.split(",")[0].trim();
      if (StringUtils.isNotBlank(firstToken)) {
        return firstToken;
      }
    }
    return httpRequest.getRemoteAddr();
  }

  private String extractMobile(AppSelfServiceUser user) {
    if (user.getAppUserClientMappings() == null) return null;
    return user.getAppUserClientMappings().stream()
        .map(AppSelfServiceUserClientMapping::getClient)
        .filter(Objects::nonNull)
        .map(Client::getMobileNo)
        .filter(StringUtils::isNotBlank)
        .findFirst()
        .orElse(null);
  }

  private boolean determineMode(String email, String mobileNumber) {
    boolean hasEmail = StringUtils.isNotBlank(email);
    boolean hasMobile = StringUtils.isNotBlank(mobileNumber);

    if (hasEmail && !hasMobile) return true;
    if (hasMobile && !hasEmail) return false;

    String pref =
        env.getProperty("fineract.selfservice.notification.login.delivery-preference", "email");
    return "email".equalsIgnoreCase(pref);
  }
}
