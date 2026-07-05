package org.apache.fineract.selfservice.account.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.service.SelfServiceSinpeEnrollmentWritePlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/sinpe/enrollment")
@Component
@Tag(name = "Self SINPE Móvil Enrollment", description = "Endpoints to enroll and verify phone numbers for SINPE Móvil transactions.")
@RequiredArgsConstructor
public class SelfSinpeEnrollmentApiResource {

    private final PlatformSelfServiceSecurityContext context;
    private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
    private final SelfServiceSinpeEnrollmentWritePlatformService writePlatformService;

    @POST
    @Path("/request")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(summary = "Request SINPE Móvil Enrollment", description = "Generates and sends an OTP to the provided phone number.")
    public String requestEnrollment(final String apiRequestBodyAsJson) {
        context.authenticatedSelfServiceUser().validateHasUpdatePermission("SSBENEFICIARYTPT"); // Reusing existing permission or create new
        
        JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
        String mobileNumber = json.get("mobileNumber").getAsString();
        
        CommandProcessingResult result = writePlatformService.requestEnrollment(mobileNumber);
        return toApiJsonSerializer.serialize(result);
    }

    @POST
    @Path("/confirm")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(summary = "Confirm SINPE Móvil Enrollment", description = "Verifies the OTP and activates the phone number for SINPE Móvil.")
    public String confirmEnrollment(final String apiRequestBodyAsJson) {
        context.authenticatedSelfServiceUser().validateHasUpdatePermission("SSBENEFICIARYTPT");
        
        JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
        String mobileNumber = json.get("mobileNumber").getAsString();
        String otp = json.get("otp").getAsString();
        
        CommandProcessingResult result = writePlatformService.confirmEnrollment(mobileNumber, otp);
        return toApiJsonSerializer.serialize(result);
    }
}