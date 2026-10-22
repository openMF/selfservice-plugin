/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.stepup.domain.StepUpChannel;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;

/**
 * Where an OTP can be delivered for a self-service user: the user's e-mail and the first non-blank
 * mobile number among the clients mapped to the user (the same rule used by the login and transfer
 * notifications; business users may be mapped to several clients).
 */
public record StepUpContact(
    Long userId, String username, String firstName, String lastName, String email, String mobile) {

  public static StepUpContact from(final AppSelfServiceUser user) {
    String mobile = null;
    if (user.getAppUserClientMappings() != null) {
      mobile =
          user.getAppUserClientMappings().stream()
              .map(AppSelfServiceUserClientMapping::getClient)
              .filter(Objects::nonNull)
              .map(Client::getMobileNo)
              .filter(StringUtils::isNotBlank)
              .findFirst()
              .orElse(null);
    }
    return new StepUpContact(
        user.getId(),
        user.getUsername(),
        user.getFirstname(),
        user.getLastname(),
        StringUtils.trimToNull(user.getEmail()),
        StringUtils.trimToNull(mobile));
  }

  public boolean supports(final StepUpChannel channel) {
    return destination(channel) != null;
  }

  /** Raw destination for the channel, {@code null} if the user has none on file. */
  public String destination(final StepUpChannel channel) {
    return channel == StepUpChannel.EMAIL ? email : mobile;
  }

  /** Destination safe to show to the user, e.g. {@code j***@mail.com} or {@code ******1234}. */
  public String maskedDestination(final StepUpChannel channel) {
    final String destination = destination(channel);
    if (destination == null) {
      return null;
    }
    return channel == StepUpChannel.EMAIL ? maskEmail(destination) : maskMobile(destination);
  }

  static String maskEmail(final String email) {
    final int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }

  static String maskMobile(final String mobile) {
    final String digits = mobile.replaceAll("\\s+", "");
    if (digits.length() <= 4) {
      return "****";
    }
    return "*".repeat(Math.min(6, digits.length() - 4)) + digits.substring(digits.length() - 4);
  }
}
