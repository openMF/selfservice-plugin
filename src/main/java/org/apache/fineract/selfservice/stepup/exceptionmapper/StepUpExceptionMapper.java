/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.exceptionmapper;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.springframework.stereotype.Component;

/**
 * Maps {@link StepUpException} to the Fineract-style error body ({@code developerMessage,
 * httpStatusCode, defaultUserMessage, userMessageGlobalisationCode, errors[]}) plus the
 * machine-readable extras of the exception ({@code action}, {@code attemptsRemaining}, {@code
 * retryAfterSeconds}). 429 answers also carry a {@code Retry-After} header.
 */
@Provider
@Component
public class StepUpExceptionMapper implements ExceptionMapper<StepUpException> {

  @Override
  public Response toResponse(final StepUpException exception) {
    final ApiParameterError error =
        ApiParameterError.generalError(exception.getErrorCode(), exception.getMessage());

    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("developerMessage", exception.getMessage());
    body.put("httpStatusCode", String.valueOf(exception.getHttpStatus()));
    body.put("defaultUserMessage", exception.getMessage());
    body.put("userMessageGlobalisationCode", exception.getErrorCode());
    body.put("errors", List.of(error));
    body.putAll(exception.getDetails());

    final Response.ResponseBuilder builder =
        Response.status(exception.getHttpStatus()).entity(body).type(MediaType.APPLICATION_JSON);
    final Object retryAfter = exception.getDetails().get("retryAfterSeconds");
    if (retryAfter != null) {
      builder.header("Retry-After", String.valueOf(retryAfter));
    }
    return builder.build();
  }
}
