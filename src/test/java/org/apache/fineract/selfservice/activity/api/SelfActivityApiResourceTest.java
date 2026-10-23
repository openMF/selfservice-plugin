/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.selfservice.activity.data.ActivityData;
import org.apache.fineract.selfservice.activity.data.ActivityPageData;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.service.ActivityQueryService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfActivityApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private ActivityQueryService queryService;
  @Mock private AppSelfServiceUser user;

  private SelfActivityApiResource resource;

  @BeforeEach
  void setUp() {
    resource = new SelfActivityApiResource(context, queryService);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
    when(user.getId()).thenReturn(7L);
    when(queryService.feed(any(), any(), any(), any()))
        .thenReturn(new ActivityPageData(0, List.of()));
  }

  @Test
  void theFeedIsAlwaysThatOfTheAuthenticatedUser() {
    resource.list(null, null, null);

    verify(queryService).feed(eq(7L), any(), isNull(), isNull());
  }

  @Test
  void limitOffsetAndTypesAreParsedAndPassedOn() {
    resource.list("10", "20", "login, transfer_confirmed");

    final ArgumentCaptor<Set<ActivityType>> types = ArgumentCaptor.forClass(Set.class);
    verify(queryService).feed(eq(7L), types.capture(), eq(20), eq(10));
    assertEquals(EnumSet.of(ActivityType.LOGIN, ActivityType.TRANSFER_CONFIRMED), types.getValue());
  }

  @Test
  void theBodyFollowsFineractsPageConvention() {
    final ActivityData item =
        new ActivityData(
            1L, "LOGIN", "SUCCESS", "2026-10-23T09:30:05", "203.0.x.x", Map.of("k", "v"));
    when(queryService.feed(any(), any(), any(), any()))
        .thenReturn(new ActivityPageData(41, List.of(item)));

    final JsonObject body =
        JsonParser.parseString(resource.list(null, null, null)).getAsJsonObject();

    assertEquals(41, body.get("totalFilteredRecords").getAsLong());
    final JsonObject first = body.getAsJsonArray("pageItems").get(0).getAsJsonObject();
    assertEquals("LOGIN", first.get("type").getAsString());
    assertEquals("203.0.x.x", first.get("ipAddress").getAsString());
    assertEquals("v", first.getAsJsonObject("details").get("k").getAsString());
  }

  @Test
  void noUserIdCanBeChosenByTheCaller() {
    // The resource has no parameter for it: the only way to pick a user is the session.
    for (final java.lang.reflect.Parameter parameter :
        java.util.Arrays.stream(SelfActivityApiResource.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("list"))
            .findFirst()
            .orElseThrow()
            .getParameters()) {
      assertTrue(
          List.of("limit", "offset", "type")
              .contains(parameter.getAnnotation(jakarta.ws.rs.QueryParam.class).value()));
    }
  }

  @Test
  void badInput_isRejectedWithAValidationErrorAndNothingIsQueried() {
    for (final String[] args :
        new String[][] {
          {"abc", null, null},
          {"0", null, null},
          {"-1", null, null},
          {null, "-1", null},
          {null, "x", null},
          {null, null, "NOT_A_TYPE"},
          {null, null, "LOGIN,HACK"}
        }) {
      assertThrows(
          PlatformApiDataValidationException.class,
          () -> resource.list(args[0], args[1], args[2]),
          String.join("|", java.util.Arrays.stream(args).map(String::valueOf).toList()));
    }
    verify(queryService, never()).feed(any(), any(), any(), any());
  }

  @Test
  void blankParametersAreTreatedAsMissing() {
    resource.list(" ", "", " ");

    verify(queryService).feed(eq(7L), any(), isNull(), isNull());
  }
}
