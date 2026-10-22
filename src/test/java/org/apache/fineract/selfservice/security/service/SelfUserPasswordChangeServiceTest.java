/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformPasswordEncoder;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.selfservice.registration.data.SelfServiceForgotPasswordDataValidator;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicy;
import org.apache.fineract.useradministration.domain.PasswordValidationPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** {@code PUT /v1/self/user}: the password of the self-service user itself is changed. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfUserPasswordChangeServiceTest {

  private static final String NEW_PASSWORD = "N3w-Passw0rd!";
  private static final String BODY =
      "{\"password\":\"" + NEW_PASSWORD + "\",\"repeatPassword\":\"" + NEW_PASSWORD + "\"}";

  @Mock private AppSelfServiceUserRepository repository;
  @Mock private PlatformPasswordEncoder encoder;
  @Mock private PasswordValidationPolicyRepository policyRepository;
  @Mock private AppSelfServiceUser authenticated;
  @Mock private AppSelfServiceUser persisted;

  private SelfUserPasswordChangeService service;

  @BeforeEach
  void setUp() {
    final FromJsonHelper json = new FromJsonHelper();
    service =
        new SelfUserPasswordChangeService(
            json,
            repository,
            encoder,
            policyRepository,
            new SelfServiceForgotPasswordDataValidator(json));
    final Office office = mock(Office.class);
    when(office.getId()).thenReturn(1L);
    when(authenticated.getId()).thenReturn(42L);
    when(persisted.getId()).thenReturn(42L);
    when(persisted.getOffice()).thenReturn(office);
    when(repository.findById(42L)).thenReturn(Optional.of(persisted));
    when(encoder.encode(persisted)).thenReturn("ENCODED");
  }

  @Test
  void changesThePasswordOfTheSelfServiceUserAndNothingElse() {
    final String response = service.changePassword(authenticated, BODY);

    final InOrder order = inOrder(persisted, encoder, repository);
    order.verify(persisted).updatePassword(NEW_PASSWORD);
    order.verify(encoder).encode(persisted);
    order.verify(persisted).updatePassword("ENCODED");
    order.verify(persisted).updatePasswordResetRequired(false);
    order.verify(repository).saveAndFlush(persisted);

    final JsonObject body = JsonParser.parseString(response).getAsJsonObject();
    assertEquals(42L, body.get("resourceId").getAsLong());
    assertEquals(1L, body.get("officeId").getAsLong());
    assertTrue(body.getAsJsonObject("changes").get("password").getAsBoolean());
  }

  @Test
  void neverReturnsThePasswordOrItsHash() {
    final String response = service.changePassword(authenticated, BODY);

    assertFalse(response.contains(NEW_PASSWORD));
    assertFalse(response.contains("ENCODED"));
    assertFalse(response.contains("passwordEncoded"));
  }

  @Test
  void usesThePersistedRowOfTheAuthenticatedUser() {
    service.changePassword(authenticated, BODY);

    verify(repository).findById(42L);
    verify(authenticated, never()).updatePassword(any());
  }

  @Test
  void rejectsAnEmptyBody() {
    assertThrows(InvalidJsonException.class, () -> service.changePassword(authenticated, " "));
    assertThrows(InvalidJsonException.class, () -> service.changePassword(authenticated, null));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsParametersOtherThanThePassword() {
    assertThrows(
        UnsupportedParameterException.class,
        () ->
            service.changePassword(
                authenticated,
                "{\"password\":\"a\",\"repeatPassword\":\"a\",\"roles\":[1],\"username\":\"x\"}"));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMissingOrBlankPasswords() {
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.changePassword(authenticated, "{\"repeatPassword\":\"a\"}"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.changePassword(authenticated, "{\"password\":\"a\"}"));
    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            service.changePassword(authenticated, "{\"password\":\" \",\"repeatPassword\":\" \"}"));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsPasswordsThatDoNotMatch() {
    final PlatformApiDataValidationException e =
        assertThrows(
            PlatformApiDataValidationException.class,
            () ->
                service.changePassword(
                    authenticated, "{\"password\":\"abc\",\"repeatPassword\":\"abd\"}"));

    assertTrue(e.getErrors().stream().anyMatch(x -> x.getParameterName().equals("repeatPassword")));
    verifyNoInteractions(repository);
  }

  @Test
  void enforcesTheActivePasswordPolicy() {
    final PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(policy.getRegex()).thenReturn("^(?=.*[0-9]).{12,}$");
    when(policy.getDescription()).thenReturn("12 characters and a digit");
    when(policyRepository.findActivePasswordValidationPolicy()).thenReturn(policy);

    assertThrows(
        PlatformApiDataValidationException.class,
        () ->
            service.changePassword(
                authenticated, "{\"password\":\"short\",\"repeatPassword\":\"short\"}"));
    verifyNoInteractions(repository);

    final String ok = "LongEnough-Passw0rd";
    service.changePassword(
        authenticated, "{\"password\":\"" + ok + "\",\"repeatPassword\":\"" + ok + "\"}");
    verify(repository).saveAndFlush(persisted);
  }

  @Test
  void aUserWhoMayNotChangeTheirPasswordIsRefusedAndNothingIsSaved() {
    org.mockito.Mockito.doThrow(
            new NoAuthorizationException("Password of this user may not be modified"))
        .when(persisted)
        .updatePassword(NEW_PASSWORD);

    assertThrows(NoAuthorizationException.class, () -> service.changePassword(authenticated, BODY));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void validateRequest_appliesThePolicyWithoutTouchingTheUser() {
    final PasswordValidationPolicy policy = mock(PasswordValidationPolicy.class);
    when(policy.getRegex()).thenReturn("^(?=.*[0-9]).{12,}$");
    when(policy.getDescription()).thenReturn("12 characters and a digit");
    when(policyRepository.findActivePasswordValidationPolicy()).thenReturn(policy);

    assertThrows(
        PlatformApiDataValidationException.class,
        () -> service.validateRequest("{\"password\":\"short\",\"repeatPassword\":\"short\"}"));
    assertEquals(NEW_PASSWORD, service.validateRequest(BODY));
    verifyNoInteractions(repository);
  }
}
