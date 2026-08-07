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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.selfservice.registration.exception.SelfServiceEnrollmentConflictException;
import org.springframework.stereotype.Component;

@Provider
@Component
public class SelfServiceEnrollmentConflictExceptionMapper
    implements ExceptionMapper<SelfServiceEnrollmentConflictException> {
    
  @Override
  public Response toResponse(SelfServiceEnrollmentConflictException exception) {
    ApiParameterError error =
        ApiParameterError.parameterError(
            exception.getUserMessageGlobalisationCode(),
            exception.getMessage(),
            exception.getParameterName(),
            null); 
            
    Map<String, Object> body = new HashMap<>();
    
    body.put("developerMessage", exception.getMessage());
    body.put("httpStatusCode", "409");
    body.put("defaultUserMessage", exception.getMessage());
    body.put("userMessageGlobalisationCode", exception.getUserMessageGlobalisationCode());
    body.put("errors", List.of(error));
    
    if (exception.getUserId() != null) {
      body.put("userId", exception.getUserId());
    }
    if (exception.getPendingConfirmation() != null) {
      body.put("pendingConfirmation", exception.getPendingConfirmation());
    }
    if (exception.getOnboarding() != null) {
      body.put("onboarding", exception.getOnboarding());
    }
    
    return Response.status(Response.Status.CONFLICT)
        .entity(body)
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}