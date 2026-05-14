/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.exceptionmapper;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.selfservice.registration.exception.SelfServiceEnrollmentConflictException;
import org.springframework.stereotype.Component;

/**
 * Maps {@link SelfServiceEnrollmentConflictException} to an HTTP conflict response.
 *
 * @since 1.15.0
 */
@Provider
@Component
public class SelfServiceEnrollmentConflictExceptionMapper
    implements ExceptionMapper<SelfServiceEnrollmentConflictException> {

  /**
   * Builds the conflict response body for a self-enrollment constraint violation.
   *
   * @param exception the enrollment conflict to serialize
   * @return HTTP 409 response containing the conflict details
   */
  @Override
  public Response toResponse(SelfServiceEnrollmentConflictException exception) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("defaultUserMessage", exception.getMessage());
    error.put("parameterName", exception.getParameterName());
    error.put("developerMessage", exception.getMessage());
    error.put("userMessageGlobalisationCode", exception.getUserMessageGlobalisationCode());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        "developerMessage",
        "The request caused a data integrity issue to be fired by the database.");
    body.put("httpStatusCode", "409");
    body.put("defaultUserMessage", exception.getMessage());
    body.put("userMessageGlobalisationCode", exception.getUserMessageGlobalisationCode());
    body.put("errors", List.of(error));

    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(body)
        .build();
  }
}
