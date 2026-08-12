/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.data;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.springframework.stereotype.Component;

/**
 * Validates JSON payloads for self-service forgot-password (request) and renew-password endpoints
 * using the standard Fineract {@link DataValidatorBuilder} / {@link
 * PlatformApiDataValidationException} pattern.
 */
@Component
@RequiredArgsConstructor
public class SelfServiceForgotPasswordDataValidator {

  private final FromJsonHelper fromApiJsonHelper;

  // -------------------------------------------------------------------------
  // Request password reset
  // -------------------------------------------------------------------------

  /**
   * Validates the request-reset payload and returns the extracted username.
   *
   * @param json raw request body
   * @return non-blank username
   */
  public String validateAndExtractUsername(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, json, SelfServiceApiConstants.FORGOT_PASSWORD_REQUEST_DATA_PARAMETERS);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(SelfServiceApiConstants.RESOURCE_NAME_PASSWORD_RESET);

    final JsonElement element = this.fromApiJsonHelper.parse(json);
    final String username =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.usernameParamName, element);

    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.usernameParamName)
        .value(username)
        .notBlank();

    throwExceptionIfValidationWarningsExist(dataValidationErrors);
    return username;
  }

  // -------------------------------------------------------------------------
  // Renew password
  // -------------------------------------------------------------------------

  /**
   * Validates the renew-password payload.
   *
   * @param json raw request body
   * @return extracted values (password, repeatPassword, externalAuthenticationToken)
   */
  public RenewPasswordData validateForRenew(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }

    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    this.fromApiJsonHelper.checkForUnsupportedParameters(
        typeOfMap, json, SelfServiceApiConstants.FORGOT_PASSWORD_RENEW_DATA_PARAMETERS);

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(SelfServiceApiConstants.RESOURCE_NAME_PASSWORD_RENEW);

    final JsonElement element = this.fromApiJsonHelper.parse(json);

    final String password =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.passwordParamName, element);
    final String repeatPassword =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.repeatPasswordParamName, element);
    final String externalToken =
        this.fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.externalAuthenticationTokenParamName, element);

    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .notBlank();

    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.repeatPasswordParamName)
        .value(repeatPassword)
        .notBlank();

    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.externalAuthenticationTokenParamName)
        .value(externalToken)
        .notBlank();

    // Passwords must match (only when both are present)
    if (StringUtils.isNotBlank(password)
        && StringUtils.isNotBlank(repeatPassword)
        && !password.equals(repeatPassword)) {
      baseDataValidator
          .reset()
          .parameter(SelfServiceApiConstants.repeatPasswordParamName)
          .value(repeatPassword)
          .failWithCode(SelfServiceApiConstants.ERROR_PASSWORDS_DO_NOT_MATCH);
    }

    throwExceptionIfValidationWarningsExist(dataValidationErrors);
    return new RenewPasswordData(password, repeatPassword, externalToken);
  }

  /**
   * Validates the new password against the active tenant password-validation policy.
   *
   * @param password plain-text password
   * @param policy active policy (may be null)
   */
  public void validatePasswordAgainstPolicy(
      final String password, final PasswordValidationPolicy policy) {
    if (policy == null || StringUtils.isBlank(policy.getRegex())) {
      return;
    }
    if (password.matches(policy.getRegex())) {
      return;
    }

    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
    final DataValidatorBuilder baseDataValidator =
        new DataValidatorBuilder(dataValidationErrors)
            .resource(SelfServiceApiConstants.RESOURCE_NAME_PASSWORD_RENEW);

    baseDataValidator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .failWithCode(
            SelfServiceApiConstants.ERROR_PASSWORD_POLICY,
            policy.getDescription() != null ? policy.getDescription() : "complexity requirements");

    throwExceptionIfValidationWarningsExist(dataValidationErrors);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private void throwExceptionIfValidationWarningsExist(
      final List<ApiParameterError> dataValidationErrors) {
    if (!dataValidationErrors.isEmpty()) {
      throw new PlatformApiDataValidationException(dataValidationErrors);
    }
  }

  /** Immutable holder for validated renew-password fields. */
  public record RenewPasswordData(
      String password, String repeatPassword, String externalAuthenticationToken) {}
}