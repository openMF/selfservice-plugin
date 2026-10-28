/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.util.Locale;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;

/** Human readable action names used inside the OTP message (Spanish default, English fallback). */
final class StepUpMessages {

  private StepUpMessages() {}

  static String actionLabel(final StepUpAction action, final Locale locale) {
    final boolean english = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
    return switch (action) {
      case TPT_TRANSFER -> english ? "third-party transfer" : "transferencia a terceros";
      case OWN_TRANSFER ->
          english ? "transfer between your accounts" : "transferencia entre tus cuentas";
      case CONFIRM_TRANSFER -> english ? "transfer confirmation" : "confirmación de transferencia";
      case CHANGE_PASSWORD -> english ? "password change" : "cambio de contraseña";
      case MANAGE_AUTHENTICATOR ->
          english ? "authenticator app change" : "cambio de la app de autenticación";
    };
  }
}
