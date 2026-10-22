/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.selfservice.registration.SelfServiceApiConstants;
import org.apache.fineract.selfservice.registration.data.SelfServiceForgotPasswordDataValidator;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets an authenticated self-service user change their own password ({@code PUT /v1/self/user}).
 *
 * <p>The password is changed on the self-service user itself ({@code m_appselfservice_user}), the
 * same table the login reads and the forgot-password flow writes. It must never be routed through
 * the Fineract-core user API: that API looks the id up in the back-office user table ({@code
 * m_appuser}), which is a different table with its own ids.
 */
@Component
@RequiredArgsConstructor
public class SelfUserPasswordChangeService {

  static final String RESOURCE_NAME = "selfservice.user";

  private static final Set<String> SUPPORTED_PARAMETERS =
      Set.of(
          SelfServiceApiConstants.passwordParamName,
          SelfServiceApiConstants.repeatPasswordParamName);
  private static final Gson GSON = new Gson();

  private final FromJsonHelper fromApiJsonHelper;
  private final AppSelfServiceUserRepository appSelfServiceUserRepository;
  private final PlatformPasswordEncoder platformPasswordEncoder;
  private final PasswordValidationPolicyRepository passwordValidationPolicyRepository;
  private final SelfServiceForgotPasswordDataValidator passwordPolicyValidator;

  /**
   * Validates the request and changes the password of {@code authenticatedUser}.
   *
   * @return the response body: {@code {"officeId", "resourceId", "changes": {"password": true}}}
   *     (the password and its hash are never returned)
   */
  @Transactional
  public String changePassword(final AppSelfServiceUser authenticatedUser, final String json) {
    final String password = validate(json);
    passwordPolicyValidator.validatePasswordAgainstPolicy(
        password, passwordValidationPolicyRepository.findActivePasswordValidationPolicy());

    // The authenticated user may be detached: always change the persisted row.
    final AppSelfServiceUser user =
        appSelfServiceUserRepository.findById(authenticatedUser.getId()).orElseThrow();

    user.updatePassword(password);
    final String encodedPassword = platformPasswordEncoder.encode(user);
    user.updatePassword(encodedPassword);
    user.updatePasswordResetRequired(false);
    appSelfServiceUserRepository.saveAndFlush(user);

    final Map<String, Object> response = new LinkedHashMap<>();
    response.put("officeId", user.getOffice().getId());
    response.put("resourceId", user.getId());
    response.put("changes", Map.of(SelfServiceApiConstants.passwordParamName, true));
    return GSON.toJson(response);
  }

  /** Returns the new password once the body is known to be well formed. */
  private String validate(final String json) {
    if (StringUtils.isBlank(json)) {
      throw new InvalidJsonException();
    }
    final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
    fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, SUPPORTED_PARAMETERS);

    final JsonElement element = fromApiJsonHelper.parse(json);
    final String password =
        fromApiJsonHelper.extractStringNamed(SelfServiceApiConstants.passwordParamName, element);
    final String repeatPassword =
        fromApiJsonHelper.extractStringNamed(
            SelfServiceApiConstants.repeatPasswordParamName, element);

    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);
    validator
        .reset()
        .parameter(SelfServiceApiConstants.passwordParamName)
        .value(password)
        .notBlank();
    validator
        .reset()
        .parameter(SelfServiceApiConstants.repeatPasswordParamName)
        .value(repeatPassword)
        .notBlank();
    if (StringUtils.isNotBlank(password)
        && StringUtils.isNotBlank(repeatPassword)
        && !password.equals(repeatPassword)) {
      validator
          .reset()
          .parameter(SelfServiceApiConstants.repeatPasswordParamName)
          .failWithCode("do.not.match", "The two passwords do not match.");
    }
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
    return password;
  }
}
