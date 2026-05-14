/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.selfservice.security.domain.PlatformSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceRoleReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class TenantAwareJpaPlatformSelfServiceUserDetailsServiceTest {

  @Mock private PlatformSelfServiceUserRepository platformUserRepository;
  @Mock private SelfServiceRoleReadPlatformService roleReadPlatformService;

  @InjectMocks private TenantAwareJpaPlatformSelfServiceUserDetailsService service;

  private AppSelfServiceUser activeUser;

  @BeforeEach
  void setUp() {
    activeUser = mock(AppSelfServiceUser.class);
  }

  @Test
  void loadUserByUsername_throwsWhenSelfServiceRoleMissingOrDisabled() {
    when(platformUserRepository.findByUsernameAndDeletedAndEnabled(
            "roberto@gmail.com", false, true))
        .thenReturn(activeUser);
    // Present but not flagged as self-service or without an enabled self-service role
    when(activeUser.isSelfServiceUser()).thenReturn(false);

    assertThatThrownBy(() -> service.loadUserByUsername("roberto@gmail.com"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  void loadUserByUsername_throwsUsernameNotFoundException_whenUserNotFound() {
    when(platformUserRepository.findByUsernameAndDeletedAndEnabled("unknown@test.com", false, true))
        .thenReturn(null);

    assertThatThrownBy(() -> service.loadUserByUsername("unknown@test.com"))
        .isInstanceOf(UsernameNotFoundException.class)
        .hasMessageContaining("unknown@test.com");
  }

  @Test
  void loadUserByUsername_throwsUsernameNotFoundException_whenUserIsDeleted() {
    // deleted=false, enabled=true means we only look for active users.
    // A deleted user won't match these criteria, so the repository returns null.
    when(platformUserRepository.findByUsernameAndDeletedAndEnabled("deleted@test.com", false, true))
        .thenReturn(null);

    assertThatThrownBy(() -> service.loadUserByUsername("deleted@test.com"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  void loadUserByUsername_throwsUsernameNotFoundException_whenUserIsDisabled() {
    when(platformUserRepository.findByUsernameAndDeletedAndEnabled(
            "disabled@test.com", false, true))
        .thenReturn(null);

    assertThatThrownBy(() -> service.loadUserByUsername("disabled@test.com"))
        .isInstanceOf(UsernameNotFoundException.class);
  }
}
