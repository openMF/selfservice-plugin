/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.notification.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Determines whether self-service notifications should be delivered via email or SMS.
 *
 * <p>Reusable across authentication, transfers, beneficiaries, registration, SINPE, etc.
 * Multi-tenant safe: preference is application-scoped; tenant routing is handled by the
 * notification event / listener layer.
 *
 * <ul>
 *   <li>Email present, mobile absent → email
 *   <li>Mobile present, email absent → SMS
 *   <li>Both or neither → {@code fineract.selfservice.notification.login.delivery-preference}
 *       (default {@code email})
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryModeUtil {

  public static final String DELIVERY_PREFERENCE_PROPERTY =
      "fineract.selfservice.notification.login.delivery-preference";

  public static final String DEFAULT_PREFERENCE = "email";

  private final Environment env;

  /**
   * @param email contact email (may be blank/null)
   * @param mobileNumber contact mobile (may be blank/null)
   * @return {@code true} for email delivery, {@code false} for SMS
   */
  public boolean determineMode(String email, String mobileNumber) {
    boolean hasEmail = StringUtils.isNotBlank(email);
    boolean hasMobile = StringUtils.isNotBlank(mobileNumber);

    if (hasEmail && !hasMobile) {
      log.info("NotificationDeliveryModeUtil: email only → emailMode=true");
      return true;
    }
    if (hasMobile && !hasEmail) {
      log.info("NotificationDeliveryModeUtil: mobile only → emailMode=false");
      return false;
    }

    String pref = env.getProperty(DELIVERY_PREFERENCE_PROPERTY, DEFAULT_PREFERENCE);
    boolean emailMode = "email".equalsIgnoreCase(pref);
    log.info(
        "NotificationDeliveryModeUtil: both/neither present, preference='{}' → emailMode={}",
        pref,
        emailMode);
    return emailMode;
  }

  /** Convenience overload when only a preference override is needed for tests. */
  public boolean determineMode(String email, String mobileNumber, String preferenceOverride) {
    boolean hasEmail = StringUtils.isNotBlank(email);
    boolean hasMobile = StringUtils.isNotBlank(mobileNumber);

    if (hasEmail && !hasMobile) {
      return true;
    }
    if (hasMobile && !hasEmail) {
      return false;
    }

    String pref =
        StringUtils.isNotBlank(preferenceOverride)
            ? preferenceOverride
            : env.getProperty(DELIVERY_PREFERENCE_PROPERTY, DEFAULT_PREFERENCE);
    return "email".equalsIgnoreCase(pref);
  }
}