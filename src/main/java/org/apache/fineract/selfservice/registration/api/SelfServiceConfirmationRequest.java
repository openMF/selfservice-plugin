package org.apache.fineract.selfservice.registration.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Swagger schema representing the JSON payload for self-service enrollment confirmation. */
public class SelfServiceConfirmationRequest {
  @Schema(
      description = "The token sent to the user via email or SMS.",
      example = "12345678-1234-5678-1234-567812345678")
  private String externalAuthenticationToken;

  @Schema(
      description =
          "Legacy request identifier (used only if externalAuthenticationToken is not provided).",
      example = "123")
  private Long requestId;

  @Schema(
      description =
          "Legacy authentication token (used only if externalAuthenticationToken is not provided).",
      example = "a1b2c3d4")
  private String authenticationToken;

  public String getExternalAuthenticationToken() {
    return externalAuthenticationToken;
  }

  public void setExternalAuthenticationToken(String externalAuthenticationToken) {
    this.externalAuthenticationToken = externalAuthenticationToken;
  }

  public Long getRequestId() {
    return requestId;
  }

  public void setRequestId(Long requestId) {
    this.requestId = requestId;
  }

  public String getAuthenticationToken() {
    return authenticationToken;
  }

  public void setAuthenticationToken(String authenticationToken) {
    this.authenticationToken = authenticationToken;
  }
}
