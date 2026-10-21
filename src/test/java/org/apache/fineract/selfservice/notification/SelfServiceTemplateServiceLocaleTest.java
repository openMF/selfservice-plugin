/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateEntity;
import org.apache.fineract.template.domain.TemplateRepository;
import org.apache.fineract.template.domain.TemplateType;
import org.junit.jupiter.api.Test;

class SelfServiceTemplateServiceLocaleTest {

  private static final String BASE = "SELF_SERVICE_STEP_UP_OTP_SMS";

  private static Template template(final String name, final String text) {
    final Template template = mock(Template.class);
    when(template.getName()).thenReturn(name);
    when(template.getText()).thenReturn(text);
    when(template.getMappers()).thenReturn(List.of());
    return template;
  }

  private static SelfServiceTemplateService serviceWith(final Template... templates) {
    final TemplateRepository repository = mock(TemplateRepository.class);
    when(repository.findByEntityAndType(TemplateEntity.CLIENT, TemplateType.SMS))
        .thenReturn(List.of(templates));
    return new SelfServiceTemplateService(repository);
  }

  @Test
  void candidateNames_preferLanguageVariantThenBase() {
    assertEquals(
        List.of(BASE + "_EN", BASE),
        SelfServiceTemplateService.candidateTemplateNames(BASE, Map.of("locale", "en_US")));
    assertEquals(
        List.of(BASE + "_ES", BASE),
        SelfServiceTemplateService.candidateTemplateNames(BASE, Map.of("locale", "es-MX")));
    assertEquals(
        List.of(BASE + "_EN", BASE),
        SelfServiceTemplateService.candidateTemplateNames(BASE, Map.of("locale", "en")));
  }

  @Test
  void candidateNames_withoutLocale_isJustTheBaseName() {
    assertEquals(List.of(BASE), SelfServiceTemplateService.candidateTemplateNames(BASE, Map.of()));
    assertEquals(List.of(BASE), SelfServiceTemplateService.candidateTemplateNames(BASE, null));
    assertEquals(
        List.of(BASE),
        SelfServiceTemplateService.candidateTemplateNames(BASE, Map.of("locale", "  ")));
  }

  @Test
  void english_usesTheEnglishVariantWhenPresent() {
    final SelfServiceTemplateService service =
        serviceWith(
            template(BASE, "Tu codigo ${authCode}"), template(BASE + "_EN", "Code ${authCode}"));

    final String message =
        service.mergeTemplate(
            SelfServiceNotificationEvent.Type.STEP_UP_OTP,
            "SMS",
            Map.of("locale", "en_US", "authCode", "424242"));

    assertEquals("Code 424242", message);
  }

  @Test
  void spanish_fallsBackToTheBaseTemplate() {
    final SelfServiceTemplateService service =
        serviceWith(
            template(BASE, "Tu codigo ${authCode}"), template(BASE + "_EN", "Code ${authCode}"));

    final String message =
        service.mergeTemplate(
            SelfServiceNotificationEvent.Type.STEP_UP_OTP,
            "SMS",
            Map.of("locale", "es_MX", "authCode", "424242"));

    assertEquals("Tu codigo 424242", message);
  }

  @Test
  void existingTemplatesWithoutVariant_behaveAsBefore() {
    final String name = "SELF_SERVICE_LOGIN_SUCCESS_SMS";
    final SelfServiceTemplateService service = serviceWith(template(name, "Hi ${firstname}"));

    final String message =
        service.mergeTemplate(
            SelfServiceNotificationEvent.Type.LOGIN_SUCCESS,
            "SMS",
            Map.of("locale", "en_US", "firstname", "Ana"));

    assertEquals("Hi Ana", message);
  }

  @Test
  void missingTemplate_returnsTheDefaultMessage() {
    final SelfServiceTemplateService service = serviceWith();

    final String message =
        service.mergeTemplate(SelfServiceNotificationEvent.Type.STEP_UP_OTP, "SMS", Map.of());

    assertEquals("Notification for event: STEP_UP_OTP via SMS", message);
  }
}
