/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.useradministration.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMappingRepository;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class SelfServiceUserAdminWritePlatformServiceImpl
    implements SelfServiceUserAdminWritePlatformService {

  private static final String USER_CLIENT_MAPPING_UNIQUE_CONSTRAINT = "appuser_id_client_id";
  private static final String CLIENT_MAPPING_UNIQUE_CONSTRAINT = "unique_self_client";

  private final AppSelfServiceUserRepository userRepository;
  private final AppSelfServiceUserClientMappingRepository mappingRepository;
  private final ClientRepositoryWrapper clientRepositoryWrapper;
  private final AppSelfServiceUserReadPlatformService readPlatformService;
  private final PlatformSecurityContext context;

  @Override
  @Transactional
  public CommandProcessingResult activate(Long userId) {
    AppSelfServiceUser user = findUser(userId);
    user.enable();
    userRepository.saveAndFlush(user);
    return CommandProcessingResult.withChanges(userId, Map.of("enabled", true));
  }

  @Override
  @Transactional
  public CommandProcessingResult inactivate(Long userId) {
    AppSelfServiceUser user = findUser(userId);
    user.disable();
    userRepository.saveAndFlush(user);
    return CommandProcessingResult.withChanges(userId, Map.of("enabled", false));
  }

  @Override
  @Transactional
  public CommandProcessingResult linkClient(Long userId, Long clientId) {
    AppSelfServiceUser user = findUser(userId);
    clientRepositoryWrapper.getClientByClientIdAndHierarchy(clientId, hierarchySearchString());

    if (mappingRepository.existsByAppUserIdAndClientId(userId, clientId)) {
      throw duplicateUserClientMapping(userId, clientId);
    }
    if (mappingRepository.existsByClientId(clientId)) {
      throw clientAlreadyLinked(clientId);
    }

    try {
      mappingRepository.saveClientUserMapping(user.getId(), clientId);
    } catch (DataIntegrityViolationException e) {
      throw translateClientMappingConstraintViolation(userId, clientId, e);
    }
    return CommandProcessingResult.withChanges(userId, Map.of("clientId", clientId));
  }

  @Override
  @Transactional
  public CommandProcessingResult delinkClient(Long userId, Long clientId) {
    findUser(userId);
    clientRepositoryWrapper.getClientByClientIdAndHierarchy(clientId, hierarchySearchString());
    if (!mappingRepository.existsByAppUserIdAndClientId(userId, clientId)) {
      throw new PlatformDataIntegrityException(
          "error.msg.self.service.user.client.mapping.not.found",
          "Self-service user " + userId + " is not linked to client " + clientId + ".",
          "clientId",
          clientId);
    }
    mappingRepository.deleteByAppUserIdAndClientId(userId, clientId);
    return CommandProcessingResult.withChanges(userId, Map.of("clientId", clientId));
  }

  @Override
  @Transactional
  @CacheEvict(
      value = {
        "appSelfServiceUserClientFetchByClientId",
        "appSelfServiceUserUserClientFetchByAppuserUsername",
        "appSelfServiceUserUserClientFetchByAppUserId"
      },
      allEntries = true)
  public CommandProcessingResult delete(Long userId) {
    AppSelfServiceUser user = findUser(userId);
    user.delete();
    userRepository.saveAndFlush(user);
    return CommandProcessingResult.withChanges(userId, Map.of("deleted", true));
  }

  private AppSelfServiceUser findUser(Long userId) {
    return readPlatformService.retrieveSelfServiceUserDomainForAdmin(userId);
  }

  private String hierarchySearchString() {
    return context.officeHierarchy() + "%";
  }

  private PlatformDataIntegrityException translateClientMappingConstraintViolation(
      Long userId, Long clientId, DataIntegrityViolationException exception) {
    String message = exception.getMostSpecificCause().getMessage();
    if (message != null && message.contains(USER_CLIENT_MAPPING_UNIQUE_CONSTRAINT)) {
      return duplicateUserClientMapping(userId, clientId);
    }
    if (message != null && message.contains(CLIENT_MAPPING_UNIQUE_CONSTRAINT)) {
      return clientAlreadyLinked(clientId);
    }
    throw exception;
  }

  private PlatformDataIntegrityException duplicateUserClientMapping(Long userId, Long clientId) {
    return new PlatformDataIntegrityException(
        "error.msg.self.service.user.client.mapping.duplicate",
        "Self-service user " + userId + " is already linked to client " + clientId + ".",
        "clientId",
        clientId);
  }

  private PlatformDataIntegrityException clientAlreadyLinked(Long clientId) {
    return new PlatformDataIntegrityException(
        "error.msg.self.service.client.already.linked",
        "Client " + clientId + " is already linked to a self-service user.",
        "clientId",
        clientId);
  }
}
