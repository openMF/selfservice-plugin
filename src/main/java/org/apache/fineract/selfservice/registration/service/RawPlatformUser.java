/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.service;

import java.util.Collections;
import org.apache.fineract.infrastructure.security.domain.PlatformUser;
import org.springframework.security.core.userdetails.User;

final class RawPlatformUser extends User implements PlatformUser {

  private static final long serialVersionUID = 1L;
  private static final String USERNAME_PLACEHOLDER = "selfservice-encoder";

  RawPlatformUser(String rawPassword) {
    super(USERNAME_PLACEHOLDER, rawPassword, Collections.emptyList());
  }
}
