/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.infrastructure.security.constants.TwoFactorConstants;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.service.SelfServiceOnboardingStepService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.client.service.SelfServiceClientReadPlatformService;
import org.apache.fineract.selfservice.kyc.service.KycFeatureStatusReadService;
import org.apache.fineract.selfservice.notification.NotificationContext;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.notification.util.NotificationDeliveryModeUtil;
import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserData;
import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserKycData;
import org.apache.fineract.selfservice.security.exception.SelfServiceDisabledException;
import org.apache.fineract.selfservice.security.exception.SelfServiceLockedException;
import org.apache.fineract.selfservice.security.exception.SelfServicePasswordResetRequiredException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.security.service.SelfServiceAuthenticationTokenService;
import org.apache.fineract.selfservice.security.service.SelfServiceDeviceFingerprintService;
import org.apache.fineract.selfservice.security.service.SelfServiceOfficeAddressReadService;
import org.apache.fineract.selfservice.security.util.DeviceFingerprintUtil;
import org.apache.fineract.selfservice.security.util.DeviceFingerprintUtil.DeviceSignals;
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
        "An API capability that allows client applications to verify authentication details using"
            + " HTTP Basic Authentication.")
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
  private final KycFeatureStatusReadService kycFeatureStatusReadService;
  private final SelfServiceOfficeAddressReadService officeAddressReadPlatformService;
  private final SelfServiceAuthenticationTokenService tokenService;
  private final NotificationDeliveryModeUtil notificationDeliveryModeUtil;
  private final TransactionDateUtil transactionDateUtil;
  private final SelfServiceDeviceFingerprintService deviceFingerprintService;
  private final SelfServiceOnboardingStepService onboardingStepService;

  private final Gson gson = new Gson();

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Verify authentication",
      description =
          "Authenticates the credentials provided and returns roles, permissions, device-aware"
              + " login handling, and onboarding step progress (DB-driven enrollment steps)."
              + " Onboarding is returned even when the account is disabled (pending confirmation)"
              + " or no steps have been completed yet.")
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

    AuthenticateRequest request = gson.fromJson(apiRequestBodyAsJson, AuthenticateRequest.class);
    if (request == null) {
      throw new IllegalArgumentException(
          "Invalid JSON in BODY (no longer URL param; see FINERACT-726) of POST to /authentication:"
              + " "
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
      publishNotificationEvent(
          SelfServiceNotificationEvent.Type.LOGIN_FAILURE,
          failedUser,
          request.username,
          httpRequest,
          null);
      // Pending confirmation / disabled: still return onboarding progress
      return serializeWithOnboardingOnly(request.username, failedUser);
    } catch (SelfServiceLockedException ex) {
      AppSelfServiceUser failedUser = ex.getUser();
      publishNotificationEvent(
          SelfServiceNotificationEvent.Type.LOGIN_FAILURE,
          failedUser,
          request.username,
          httpRequest,
          null);
      throw ex;
    } catch (BadCredentialsException ex) {
      AppSelfServiceUser failedUser =
          this.appUserRepository.findAppSelfServiceUserByName(request.username);
      if (failedUser != null) {
        publishNotificationEvent(
            SelfServiceNotificationEvent.Type.LOGIN_FAILURE,
            failedUser,
            request.username,
            httpRequest,
            null);
        // Pending confirmation: Spring often surfaces this as BadCredentials, not Disabled
        if (!failedUser.isEnabled()) {
          return serializeWithOnboardingOnly(request.username, failedUser);
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

      final AppSelfServiceUser principal = (AppSelfServiceUser) authenticationCheck.getPrincipal();
      final SelfServiceAuthenticationTokenService.TokenPair tokens =
          tokenService.generateTokens(principal.getId(), request.username);

      final byte[] base64AccessKey =
          Base64.getEncoder().encode(tokens.accessToken().getBytes(StandardCharsets.UTF_8));
      final byte[] base64RefreshKey =
          Base64.getEncoder().encode(tokens.refreshToken().getBytes(StandardCharsets.UTF_8));

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
                    new String(base64AccessKey, StandardCharsets.UTF_8))
                .setRefreshToken(new String(base64RefreshKey, StandardCharsets.UTF_8))
                .setAuthenticated(true)
                .setShouldRenewPassword(true)
                .setTwoFactorAuthenticationRequired(isTwoFactorRequired);
        throw new SelfServicePasswordResetRequiredException(authenticatedUserData);
      } else {
        processDeviceFingerprintOnLogin(principal, apiRequestBodyAsJson, httpRequest);

        publishNotificationEvent(
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            principal,
            request.username,
            httpRequest,
            null);

        Collection<Long> clientList =
            returnClientList
                ? clientReadPlatformService.retrieveSelfServiceUserClients(userId)
                : null;
        Long clientId = getClientId(clientList);
        String country = officeAddressReadPlatformService.retrieveOfficeCountryByClientId(clientId);

        OnboardingProgressData onboarding = resolveOnboardingProgress(principal.getId());

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
                    new String(base64AccessKey, StandardCharsets.UTF_8))
                .setRefreshToken(new String(base64RefreshKey, StandardCharsets.UTF_8))
                .setTwoFactorAuthenticationRequired(isTwoFactorRequired)
                .setClients(returnClientList ? clientList : null)
                .setKycValidations(getKycStatusForUser(clientId))
                .setCountry(country)
                .setOnboarding(onboarding);
      }
    }

    return this.apiJsonSerializerService.serialize(authenticatedUserData);
  }

  /**
   * Account disabled (e.g. enrollment code not confirmed): return authenticated=false with
   * onboarding progress so the client can resume the enrollment flow.
   */
  private String serializeWithOnboardingOnly(String username, AppSelfServiceUser failedUser) {
    OnboardingProgressData onboarding =
        failedUser != null
            ? resolveOnboardingProgress(failedUser.getId())
            : emptyOnboardingProgress();

    SelfServiceAuthenticatedUserData body =
        new SelfServiceAuthenticatedUserData()
            .setUsername(username)
            .setUserId(failedUser != null ? failedUser.getId() : null)
            .setAuthenticated(false)
            .setOnboarding(onboarding);

    log.info(
        "LOGIN: account disabled/pending confirmation for username={}, returning onboarding",
        username);
    return this.apiJsonSerializerService.serialize(body);
  }

  /**
   * Loads onboarding progress; never returns null. Initializes rows if missing. Failures fall back
   * to an empty progress object so login still succeeds.
   */
  private OnboardingProgressData resolveOnboardingProgress(Long userId) {
    if (userId == null) {
      return emptyOnboardingProgress();
    }
    try {
      OnboardingProgressData progress = onboardingStepService.getOrInitProgress(userId);
      if (progress != null) {
        return progress;
      }
    } catch (Exception e) {
      log.warn(
          "LOGIN: Could not load onboarding progress for userId={} (non-fatal)", userId, e);
    }
    return emptyOnboardingProgress();
  }

  private OnboardingProgressData emptyOnboardingProgress() {
    return OnboardingProgressData.builder()
        .onboardingComplete(false)
        .totalSteps(0)
        .completedSteps(0)
        .progressPercent(0)
        .currentStep(null)
        .steps(List.of())
        .build();
  }

  private void processDeviceFingerprintOnLogin(
      AppSelfServiceUser user, String apiRequestBodyAsJson, HttpServletRequest httpRequest) {
    try {
      JsonObject body = parseBodyObject(apiRequestBodyAsJson);
      DeviceSignals signals = DeviceFingerprintUtil.from(httpRequest, body);

      boolean hasPriorDevices = deviceFingerprintService.hasAnyDevice(user.getId());
      boolean known =
          deviceFingerprintService.isKnownDevice(user.getId(), signals.fingerprintHash());

      if (hasPriorDevices && !known) {
        log.info(
            "LOGIN: Unknown device for userId={}, hashPrefix={}, ip={}",
            user.getId(),
            shortHash(signals.fingerprintHash()),
            signals.ipAddress());

        Map<String, Object> contextData = new HashMap<>();
        contextData.put(
            "ipAddress", StringUtils.defaultIfBlank(signals.ipAddress(), "Unknown"));
        contextData.put(
            "userAgent", StringUtils.defaultIfBlank(signals.userAgent(), "Unknown"));
        contextData.put(
            "deviceLabel", StringUtils.defaultIfBlank(signals.deviceLabel(), "Unknown"));
        contextData.put(
            "loginTime",
            transactionDateUtil.getCurrentTenantLocalDateTime() != null
                ? transactionDateUtil.getCurrentTenantLocalDateTime().toString()
                : "");

        publishNotificationEvent(
            SelfServiceNotificationEvent.Type.LOGIN_UNKNOWN_DEVICE,
            user,
            user.getUsername(),
            httpRequest,
            contextData);
      } else if (!hasPriorDevices) {
        log.info(
            "LOGIN: Baseline device registered for userId={} (no prior fingerprints)",
            user.getId());
      }

      deviceFingerprintService.registerOrTouch(user.getId(), signals, true);
    } catch (Exception e) {
      log.warn(
          "LOGIN: Device fingerprint processing failed for userId={} (non-fatal)",
          user != null ? user.getId() : null,
          e);
    }
  }

  private static String shortHash(String hash) {
    if (hash == null || hash.length() < 12) {
      return hash != null ? hash : "n/a";
    }
    return hash.substring(0, 12);
  }

  private JsonObject parseBodyObject(String apiRequestBodyAsJson) {
    if (StringUtils.isBlank(apiRequestBodyAsJson)) {
      return new JsonObject();
    }
    try {
      return JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    } catch (Exception e) {
      log.debug("Could not parse login body as JsonObject for fingerprint signals");
      return new JsonObject();
    }
  }

  private void publishNotificationEvent(
      SelfServiceNotificationEvent.Type type,
      AppSelfServiceUser user,
      String username,
      HttpServletRequest httpRequest,
      Map<String, Object> extraContext) {

    if (user == null) {
      return;
    }

    String mobileNumber = extractMobile(user);
    boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);
    String ipAddress = extractClientIp(httpRequest);

    Map<String, Object> contextData = new HashMap<>();
    if (extraContext != null) {
      contextData.putAll(extraContext);
    }
    contextData.putIfAbsent("ipAddress", StringUtils.defaultIfBlank(ipAddress, "Unknown"));
    contextData.putIfAbsent("username", username);

    try (NotificationContext.Scope ignored = NotificationContext.bind(type.name())) {
      applicationEventPublisher.publishEvent(
          SelfServiceNotificationEvent.withTenantContext(
              this,
              type,
              user.getId(),
              user.getFirstname(),
              user.getLastname(),
              username,
              user.getEmail(),
              mobileNumber,
              emailMode,
              ipAddress,
              httpRequest != null ? httpRequest.getLocale() : null,
              contextData));
    } catch (Exception e) {
      try (NotificationContext.Scope ignored = NotificationContext.bind(type.name())) {
        applicationEventPublisher.publishEvent(
            SelfServiceNotificationEvent.withTenantContext(
                this,
                type,
                user.getId(),
                user.getFirstname(),
                user.getLastname(),
                username,
                user.getEmail(),
                mobileNumber,
                emailMode,
                ipAddress,
                httpRequest != null ? httpRequest.getLocale() : null));
      } catch (Exception e2) {
        log.warn("Failed to publish {} notification event", type, e2);
      }
    }
  }

  private SelfServiceAuthenticatedUserKycData getKycStatusForUser(final Long clientId) {
    return kycFeatureStatusReadService.getKycFeatureStatus(clientId);
  }

  private Long getClientId(Collection<Long> clientList) {
    if (clientList == null) {
      return null;
    }
    Iterator<Long> iterator = clientList.iterator();
    if (iterator.hasNext()) {
      return iterator.next();
    }
    return null;
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
    String realIp = httpRequest.getHeader("X-Real-Ip");
    if (StringUtils.isNotBlank(realIp)) {
      return realIp.trim();
    }
    return httpRequest.getRemoteAddr();
  }

  private String extractMobile(AppSelfServiceUser user) {
    if (user == null || user.getAppUserClientMappings() == null) {
      return null;
    }
    return user.getAppUserClientMappings().stream()
        .map(AppSelfServiceUserClientMapping::getClient)
        .filter(Objects::nonNull)
        .map(Client::getMobileNo)
        .filter(StringUtils::isNotBlank)
        .findFirst()
        .orElse(null);
  }

  public static class RefreshTokenRequest {
    public String refreshToken;
  }

  @POST
  @Path("/token")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Refresh authentication token",
      description =
          "Exchanges a valid refresh token for a new access token and refresh token pair.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String refreshToken(final String apiRequestBodyAsJson) {
    RefreshTokenRequest request = gson.fromJson(apiRequestBodyAsJson, RefreshTokenRequest.class);
    if (request == null || StringUtils.isBlank(request.refreshToken)) {
      throw new IllegalArgumentException("Refresh token is missing in the request body.");
    }

    SelfServiceAuthenticationTokenService.TokenPair newTokens =
        tokenService.refreshTokens(request.refreshToken);

    byte[] base64AccessKey =
        Base64.getEncoder().encode(newTokens.accessToken().getBytes(StandardCharsets.UTF_8));
    byte[] base64RefreshKey =
        Base64.getEncoder().encode(newTokens.refreshToken().getBytes(StandardCharsets.UTF_8));

    Map<String, String> response = new HashMap<>();
    response.put(
        "base64EncodedAuthenticationKey", new String(base64AccessKey, StandardCharsets.UTF_8));
    response.put("refreshToken", new String(base64RefreshKey, StandardCharsets.UTF_8));
    return this.apiJsonSerializerService.serialize(response);
  }

  @POST
  @Path("/logout")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Logout user",
      description =
          "Invalidates the current authentication token, effectively logging the user out. A"
              + " notification is sent to the user through all enabled channels (Email, SMS,"
              + " WhatsApp, In-App).")
  @ApiResponse(responseCode = "200", description = "OK - User logged out successfully")
  public String logout(@Context HttpServletRequest httpRequest) {
    String token = extractTokenFromRequest(httpRequest);

    AppSelfServiceUser user = null;
    String username = null;
    if (token != null) {
      try {
        Long userId = tokenService.getUserIdFromToken(token);
        if (userId != null) {
          user = appUserRepository.findById(userId).orElse(null);
          if (user != null) {
            username = user.getUsername();
          }
        }
      } catch (Exception e) {
        log.warn("Failed to extract user information from token during logout", e);
      }
      tokenService.invalidateToken(token);
    }

    if (user != null) {
      publishLogoutNotificationEvent(user, username, httpRequest);
    } else {
      log.debug("Logout notification skipped: could not identify user from token");
    }

    Map<String, String> response = new HashMap<>();
    response.put("status", "success");
    response.put("message", "Logged out successfully");
    return this.apiJsonSerializerService.serialize(response);
  }

  private void publishLogoutNotificationEvent(
      AppSelfServiceUser user, String username, HttpServletRequest httpRequest) {
    String mobileNumber = extractMobile(user);
    boolean emailMode = notificationDeliveryModeUtil.determineMode(user.getEmail(), mobileNumber);

    try (NotificationContext.Scope ignored =
        NotificationContext.bind(SelfServiceNotificationEvent.Type.LOGOUT.name())) {
      applicationEventPublisher.publishEvent(
          SelfServiceNotificationEvent.withTenantContext(
              this,
              SelfServiceNotificationEvent.Type.LOGOUT,
              user.getId(),
              user.getFirstname(),
              user.getLastname(),
              username,
              user.getEmail(),
              mobileNumber,
              emailMode,
              extractClientIp(httpRequest),
              httpRequest != null ? httpRequest.getLocale() : null));
      log.info("Logout notification published for user: {}", username);
    } catch (Exception e) {
      log.warn("Failed to publish LOGOUT notification event for user: {}", username, e);
    }
  }

  private String extractTokenFromRequest(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String header = request.getHeader("Authorization");
    if (header != null && header.toLowerCase().startsWith("basic ")) {
      String base64Token = header.substring(6).trim();
      try {
        return new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return null;
  }
}