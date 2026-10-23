/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.selfservice.activity.data.ActivityPageData;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.service.ActivityQueryService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

/**
 * The activity history of the logged-in user (US-E12-05): logins, transfers, beneficiary / profile
 * / password changes and step-up, newest first.
 *
 * <p>There is no user id parameter: the history is always the one of the authenticated user. IP
 * addresses are masked and the details only hold whitelisted fields.
 */
@Path("/v1/self/activity")
@Component
@Tag(
    name = "Self Activity",
    description =
        "History of the logins and sensitive actions of the logged-in user, so that unauthorised"
            + " use can be detected.")
@RequiredArgsConstructor
public class SelfActivityApiResource {

  private static final Gson GSON = new Gson();

  private final PlatformSelfServiceSecurityContext context;
  private final ActivityQueryService queryService;

  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List my activity",
      description =
          "Newest first. `limit` defaults to 20 and is capped by the tenant setting `maxPageSize`;"
              + " `offset` defaults to 0. `type` is an optional comma separated list of activity"
              + " types (for example LOGIN,TRANSFER_CONFIRMED). The IP address is masked.")
  @ApiResponse(responseCode = "200", description = "OK")
  @ApiResponse(responseCode = "400", description = "Invalid limit, offset or type")
  public String list(
      @Parameter(description = "Page size") @QueryParam("limit") final String limit,
      @Parameter(description = "Rows to skip") @QueryParam("offset") final String offset,
      @Parameter(description = "Comma separated activity types") @QueryParam("type")
          final String type) {
    final Long userId = context.authenticatedSelfServiceUser().getId();

    final List<ApiParameterError> errors = new ArrayList<>();
    final Integer parsedLimit = parseInt("limit", limit, 1, errors);
    final Integer parsedOffset = parseInt("offset", offset, 0, errors);
    final Set<ActivityType> types = parseTypes(type, errors);
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }

    final ActivityPageData page = queryService.feed(userId, types, parsedOffset, parsedLimit);
    return GSON.toJson(page);
  }

  private static Integer parseInt(
      final String name, final String raw, final int min, final List<ApiParameterError> errors) {
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    try {
      final int value = Integer.parseInt(raw.trim());
      if (value >= min) {
        return value;
      }
    } catch (NumberFormatException ignored) {
      // reported below
    }
    errors.add(
        ApiParameterError.parameterError(
            "validation.msg.activity." + name + ".invalid",
            "The parameter '" + name + "' must be a whole number of at least " + min + ".",
            name));
    return null;
  }

  private static Set<ActivityType> parseTypes(
      final String raw, final List<ApiParameterError> errors) {
    final Set<ActivityType> types = EnumSet.noneOf(ActivityType.class);
    if (StringUtils.isBlank(raw)) {
      return types;
    }
    for (final String part : raw.split(",")) {
      final String name = part.trim().toUpperCase(Locale.ROOT);
      if (name.isEmpty()) {
        continue;
      }
      try {
        types.add(ActivityType.valueOf(name));
      } catch (IllegalArgumentException e) {
        errors.add(
            ApiParameterError.parameterError(
                "validation.msg.activity.type.unsupported", "Unsupported activity type.", "type"));
      }
    }
    return types;
  }
}
