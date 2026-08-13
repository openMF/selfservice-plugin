/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.starter;

import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.apache.fineract.selfservice.useradministration.service.AppSelfServiceUserReadPlatformService;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceUserAdminWritePlatformService;
import org.apache.fineract.selfservice.useradministration.service.SelfServiceUserAdminWritePlatformServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SelfServiceUserAdministrationConfiguration {

  @Bean
  @ConditionalOnMissingBean(SelfServiceUserAdminWritePlatformService.class)
  public SelfServiceUserAdminWritePlatformService selfServiceUserAdminWritePlatformService(
      AppSelfServiceUserRepository userRepository,
      AppSelfServiceUserClientMappingRepository mappingRepository,
      ClientRepositoryWrapper clientRepositoryWrapper,
      AppSelfServiceUserReadPlatformService readPlatformService,
      PlatformSecurityContext context) {
    return new SelfServiceUserAdminWritePlatformServiceImpl(
        userRepository, mappingRepository, clientRepositoryWrapper, readPlatformService, context);
  }
}
