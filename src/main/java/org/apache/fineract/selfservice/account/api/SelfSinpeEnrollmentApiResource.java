package org.apache.fineract.selfservice.account.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionRequest;
import org.apache.fineract.selfservice.account.service.SelfServiceSinpeEnrollmentWritePlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/sinpe/enrollment")
@Component
@Tag(
    name = "Self SINPE Móvil Enrollment",
    description = "Endpoints to enroll and verify phone numbers for SINPE Móvil transactions.")
@RequiredArgsConstructor
public class SelfSinpeEnrollmentApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
  private final SelfServiceSinpeEnrollmentWritePlatformService writePlatformService;

  @POST
  @Path("/request")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Request SINPE Móvil Enrollment",
      description = "Generates and sends an OTP to the provided phone number.")
  public String requestEnrollment(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasUpdatePermission("SSBENEFICIARYTPT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    String mobileNumber = json.get("mobileNumber").getAsString();

    CommandProcessingResult result = writePlatformService.requestEnrollment(mobileNumber);
    return toApiJsonSerializer.serialize(result);
  }

  @POST
  @Path("/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Confirm SINPE Móvil Enrollment",
      description = "Verifies the OTP and activates the phone number for SINPE Móvil.")
  public String confirmEnrollment(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasUpdatePermission("SSBENEFICIARYTPT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    String mobileNumber = json.get("mobileNumber").getAsString();
    String otp = json.get("otp").getAsString();

    CommandProcessingResult result = writePlatformService.confirmEnrollment(mobileNumber, otp);
    return toApiJsonSerializer.serialize(result);
  }

  @POST
  @Path("/subscription")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create SINPE Subscription",
      description =
          "Creates a new SINPE subscription in the external system. Requires a valid OTP.")
  public String createSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasUpdatePermission("SSBENEFICIARYTPT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    String otp = json.get("otp").getAsString();

    // Remove otp from JSON before mapping to DTO to avoid sending it to the external API
    json.remove("otp");
    String cleanJson = json.toString();

    SinpeSubscriptionRequest request =
        new com.google.gson.Gson().fromJson(cleanJson, SinpeSubscriptionRequest.class);
    CommandProcessingResult result = writePlatformService.createSubscription(request, otp);
    return toApiJsonSerializer.serialize(result);
  }

  @POST
  @Path("/subscription/edit")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Edit SINPE Subscription",
      description =
          "Edits an existing SINPE subscription in the external system. Requires a valid OTP.")
  public String editSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasUpdatePermission("SSBENEFICIARYTPT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    String otp = json.get("otp").getAsString();
    json.remove("otp");
    String cleanJson = json.toString();

    SinpeSubscriptionEditRequest request =
        new com.google.gson.Gson().fromJson(cleanJson, SinpeSubscriptionEditRequest.class);
    CommandProcessingResult result = writePlatformService.editSubscription(request, otp);
    return toApiJsonSerializer.serialize(result);
  }

  @POST
  @Path("/subscription/delete-request")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
          summary = "Request OTP for Deleting SINPE Subscription",
          description = "Generates and sends an OTP to authorize the deletion/unsubscribing of a SINPE Móvil subscription.")
  public String requestDeleteSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasDeletePermission("SSBENEFICIARYTPT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    String phoneNumber = json.get("phoneNumber").getAsString();

    CommandProcessingResult result = writePlatformService.requestDeleteSubscription(phoneNumber);
    return toApiJsonSerializer.serialize(result);
  }

  @DELETE
  @Path("/subscription/{phoneNumber}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
          summary = "Delete SINPE Subscription",
          description = "Deletes a SINPE subscription from the external system. Requires a valid OTP.")
  public String deleteSubscription(
          @PathParam("phoneNumber") final String phoneNumber, @QueryParam("otp") final String otp) {
    context.authenticatedSelfServiceUser().validateHasDeletePermission("SSBENEFICIARYTPT");

    CommandProcessingResult result = writePlatformService.deleteSubscription(phoneNumber, otp);
    return toApiJsonSerializer.serialize(result);
  }
}
