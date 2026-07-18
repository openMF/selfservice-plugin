/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.pockets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.pockets.data.PocketAccountMappingData;
import org.apache.fineract.selfservice.pockets.service.PocketAccountMappingReadPlatformService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PocketApiResourceTest {

  @Mock private PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
  @Mock private DefaultToApiJsonSerializer<PocketAccountMappingData> toApiJsonSerializer;
  @Mock private PocketAccountMappingReadPlatformService pocketAccountMappingReadPlatformService;

  private PocketApiResource resource;

  private static final String LINK_BODY =
      "{\"accountsDetail\":[{\"accountId\":11,\"accountType\":\"LOAN\"}]}";

  @BeforeEach
  void setUp() {
    resource =
        new PocketApiResource(
            commandsSourceWritePlatformService,
            toApiJsonSerializer,
            pocketAccountMappingReadPlatformService);
  }

  @Test
  void handleCommands_linkAccounts_delegatesToCommandSource() {
    CommandProcessingResult result = CommandProcessingResult.commandOnlyResult(1L);
    when(commandsSourceWritePlatformService.logCommandSource(any(CommandWrapper.class)))
        .thenReturn(result);
    when(toApiJsonSerializer.serialize(result)).thenReturn("{\"resourceId\":6}");

    String response = resource.handleCommands("linkAccounts", null, LINK_BODY);

    assertEquals("{\"resourceId\":6}", response);
    verify(commandsSourceWritePlatformService).logCommandSource(any(CommandWrapper.class));
  }

  @Test
  void handleCommands_delinkAccounts_delegatesToCommandSource() {
    CommandProcessingResult result = CommandProcessingResult.commandOnlyResult(1L);
    when(commandsSourceWritePlatformService.logCommandSource(any(CommandWrapper.class)))
        .thenReturn(result);
    when(toApiJsonSerializer.serialize(result)).thenReturn("{\"resourceId\":6}");

    String response =
        resource.handleCommands("delinkAccounts", null, "{\"pocketAccountMappingIds\":[10]}");

    assertEquals("{\"resourceId\":6}", response);
    verify(commandsSourceWritePlatformService).logCommandSource(any(CommandWrapper.class));
  }

  @Test
  void handleCommands_unknownCommand_throws() {
    assertThrows(
        UnrecognizedQueryParamException.class,
        () -> resource.handleCommands("bogus", null, LINK_BODY));
    verify(commandsSourceWritePlatformService, never()).logCommandSource(any());
  }

  @Test
  void retrieveAll_serializesReadServiceResult() {
    PocketAccountMappingData data = PocketAccountMappingData.instance(null, null, null);
    when(pocketAccountMappingReadPlatformService.retrieveAll()).thenReturn(data);
    when(toApiJsonSerializer.serialize(data)).thenReturn("{}");

    String response = resource.retrieveAll();

    assertEquals("{}", response);
    verify(pocketAccountMappingReadPlatformService).retrieveAll();
  }
}
