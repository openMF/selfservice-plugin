package org.apache.fineract.selfservice.account.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/transfer-fees")
@Component
@Tag(
    name = "Self Transfer Fees Management",
    description = "Endpoints to manage transfer fee configurations")
@RequiredArgsConstructor
public class SelfServiceTransferFeeApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SelfServiceTransferFeeRepository feeRepository;
  private final DefaultToApiJsonSerializer<SelfServiceTransferFee> toApiJsonSerializer;
  private final Gson gson = new Gson();

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get All Transfer Fees",
      description = "Retrieves all configured transfer fees.")
  public String getAll() {
    context.authenticatedSelfServiceUser().validateHasReadPermission("TRANSFER_FEE");
    return toApiJsonSerializer.serialize(feeRepository.findAll());
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Create Transfer Fee",
      description = "Creates a new transfer fee configuration.")
  public String create(final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasCreatePermission("TRANSFER_FEE");
    SelfServiceTransferFee fee = gson.fromJson(apiRequestBodyAsJson, SelfServiceTransferFee.class);
    fee.setId(null); // Ensure it's treated as a new entity
    feeRepository.save(fee);
    return toApiJsonSerializer.serialize(fee);
  }

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Update Transfer Fee",
      description = "Updates an existing transfer fee configuration.")
  public String update(@PathParam("id") Long id, final String apiRequestBodyAsJson) {
    context.authenticatedSelfServiceUser().validateHasUpdatePermission("TRANSFER_FEE");
    SelfServiceTransferFee fee =
        feeRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Fee configuration not found"));

    SelfServiceTransferFee updates =
        gson.fromJson(apiRequestBodyAsJson, SelfServiceTransferFee.class);

    // Update fields
    fee.setTransferType(updates.getTransferType());
    fee.setCurrencyCode(updates.getCurrencyCode());
    fee.setTransferMode(updates.getTransferMode());
    fee.setFeeType(updates.getFeeType());
    fee.setFeeValue(updates.getFeeValue());
    fee.setFeeCurrency(updates.getFeeCurrency());
    fee.setExchangeRate(updates.getExchangeRate());
    fee.setThresholdAmount(updates.getThresholdAmount());
    fee.setThresholdFeeValue(updates.getThresholdFeeValue());
    fee.setDescription(updates.getDescription());
    fee.setActive(updates.isActive());

    feeRepository.save(fee);
    return toApiJsonSerializer.serialize(fee);
  }

  @DELETE
  @Path("/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Delete Transfer Fee", description = "Deletes a transfer fee configuration.")
  public String delete(@PathParam("id") Long id) {
    context.authenticatedSelfServiceUser().validateHasDeletePermission("TRANSFER_FEE");
    if (!feeRepository.existsById(id)) {
      throw new IllegalArgumentException("Fee configuration not found");
    }
    feeRepository.deleteById(id);
    return "{\"status\": \"deleted\", \"resourceId\": " + id + "}";
  }
}
