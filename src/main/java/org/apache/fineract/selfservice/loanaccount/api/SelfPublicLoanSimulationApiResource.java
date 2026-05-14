/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTemplateTypeRequiredException;
import org.apache.fineract.portfolio.loanaccount.exception.NotSupportedLoanTemplateTypeException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanScheduleData;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleAssembler;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanProductData;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanSimulationDataValidator;
import org.apache.fineract.selfservice.loanaccount.service.SelfPublicLoanProductReadService;
import org.springframework.stereotype.Component;

/**
 * Public (unauthenticated) endpoints for loan application simulation. These endpoints allow
 * anonymous users to browse active loan products and calculate repayment schedules without creating
 * a self-service account.
 *
 * <p><strong>Security:</strong> No authentication is required. These endpoints are explicitly
 * whitelisted via {@code permitAll()} in {@code SelfServiceSecurityConfiguration}. The {@link
 * SelfPublicLoanSimulationDataValidator} enforces that only schedule calculations are allowed (not
 * loan submissions) and that {@code clientId} is never present.
 *
 * <p><strong>Core version dependency:</strong> Auth-bypass strategy verified against {@code
 * fineract-provider:1.15.0-SNAPSHOT} (artifact {@code 20260329.095314-5}). If the Fineract core
 * version changes, the assumption that {@link LoanScheduleAssembler#assembleLoanScheduleFrom} does
 * not call {@code authenticatedUser()} should be re-verified.
 *
 * <p><strong>Holiday handling:</strong> Since no {@code clientId} is provided, the core {@link
 * LoanScheduleAssembler} cannot resolve an office for holiday lookups. As of FINERACT-2597, the
 * assembler is null-safe for {@code officeId} and skips holiday-based repayment rescheduling when
 * no office context is available. The resulting schedule is mathematically correct but does not
 * account for office-specific holidays.
 */
@Path("/v1/self/loans/simulate")
@Component
@Tag(
    name = "Self Loan Simulation",
    description =
        "Public endpoints for loan application simulation without authentication (MX-250)")
@SecurityRequirements({})
@RequiredArgsConstructor
public class SelfPublicLoanSimulationApiResource {

  private static final Gson GSON =
      new GsonBuilder()
          .serializeNulls()
          .registerTypeAdapter(
              LocalDate.class,
              (JsonSerializer<LocalDate>)
                  (src, typeOfSrc, context) -> new JsonPrimitive(src.toString()))
          .create();

  private final SelfPublicLoanProductReadService loanProductReadService;
  private final ApiRequestParameterHelper apiRequestParameterHelper;
  private final SelfPublicLoanSimulationDataValidator dataValidator;
  private final LoanScheduleAssembler loanScheduleAssembler;
  private final DefaultToApiJsonSerializer<LoanScheduleData> loanScheduleSerializer;
  private final FromJsonHelper fromJsonHelper;

  /**
   * Retrieves all active loan products with simulation-relevant fields (currency, principal ranges,
   * interest rates, repayment configuration). This is a public, unauthenticated endpoint.
   *
   * @return JSON array of active loan products serialized from {@link SelfPublicLoanProductData}
   */
  @GET
  @Path("products")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Active Loan Products",
      description =
          "Retrieves all active loan products with simulation-relevant detail (currency,"
              + " principal ranges, interest rates, repayment configuration). Active products"
              + " are those without a close date or whose close date has not passed.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                array =
                    @ArraySchema(
                        schema =
                            @Schema(
                                implementation =
                                    SelfPublicLoanSimulationApiResourceSwagger
                                        .GetSelfPublicSimulationProductsResponse.class))))
  })
  public String retrieveAllLoanProducts() {
    final Collection<SelfPublicLoanProductData> products =
        this.loanProductReadService.retrieveAllActiveLoanProducts();
    return GSON.toJson(products);
  }

  /**
   * Returns a loan template with product-specific defaults for simulation. Only the 'individual'
   * template type is supported. This is a public, unauthenticated endpoint.
   *
   * @param productId optional loan product ID to retrieve specific defaults for; if the ID does not
   *     match any active product, a {@code ResourceNotFoundException} is thrown
   * @param templateType must be {@code "individual"}; other types are rejected
   * @return JSON representation of the selected product or all active products
   * @throws LoanTemplateTypeRequiredException if {@code templateType} is null
   * @throws NotSupportedLoanTemplateTypeException if {@code templateType} is not 'individual'
   * @throws org.apache.fineract.infrastructure.core.exception.ResourceNotFoundException if {@code
   *     productId} is provided but no active product matches
   */
  @GET
  @Path("template")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Loan Simulation Template",
      description =
          "Returns a loan template with product-specific defaults and calculation options."
              + " Only the 'individual' template type is supported for public simulation."
              + " Client-specific fields (linked savings, office-filtered staff) are excluded.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "OK")})
  public String template(
      @QueryParam("productId")
          @Parameter(description = "Loan product ID to retrieve template defaults for")
          final Long productId,
      @QueryParam("templateType") @Parameter(description = "Must be 'individual'", required = true)
          final String templateType) {

    if (templateType == null) {
      throw new LoanTemplateTypeRequiredException("Loan template type must be provided");
    }
    if (!"individual".equalsIgnoreCase(templateType)) {
      throw new NotSupportedLoanTemplateTypeException(templateType, "individual");
    }

    // Build a simplified template response with product options
    final Collection<SelfPublicLoanProductData> productOptions =
        this.loanProductReadService.retrieveAllActiveLoanProducts();

    if (productId != null) {
      final SelfPublicLoanProductData selectedProduct =
          productOptions.stream()
              .filter(p -> productId.equals(p.getId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new org.apache.fineract.infrastructure.core.exception
                          .ResourceNotFoundException(
                          "loanProduct", "id", new Object[] {productId}));
      return GSON.toJson(selectedProduct);
    }

    return GSON.toJson(productOptions);
  }

  /**
   * Calculates a loan repayment schedule based on the provided parameters. This is a public,
   * unauthenticated endpoint. Only the {@code "calculateLoanSchedule"} command is accepted; the
   * {@code clientId} field must NOT be present in the request body.
   *
   * <p>Validation is performed by {@link SelfPublicLoanSimulationDataValidator} before the request
   * reaches the core {@link LoanScheduleAssembler}. The assembler bypasses the core's {@code
   * LoanApplicationValidator.validateForCreate()} which would require {@code clientId}.
   *
   * @param commandParam must be {@code "calculateLoanSchedule"}
   * @param uriInfo JAX-RS URI context for serialization settings
   * @param apiRequestBodyAsJson loan schedule calculation parameters
   * @return JSON representation of the calculated repayment schedule
   * @throws org.apache.fineract.infrastructure.core.exception.UnrecognizedQueryParamException if
   *     {@code command} is not {@code "calculateLoanSchedule"}
   * @throws org.apache.fineract.infrastructure.core.exception.InvalidJsonException if the request
   *     body is blank or malformed
   * @throws org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException if
   *     the request body contains {@code clientId}
   */
  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Calculate Loan Repayment Schedule",
      description =
          "Calculates a loan repayment schedule based on the provided parameters. Only the"
              + " 'calculateLoanSchedule' command is accepted. The 'clientId' field must NOT be"
              + " present in the request body.")
  @RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfPublicLoanSimulationApiResourceSwagger.PostSelfPublicSimulationRequest
                              .class)))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfPublicLoanSimulationApiResourceSwagger
                                .PostSelfPublicSimulationResponse.class)))
  })
  public String calculateLoanSchedule(
      @QueryParam("command")
          @Parameter(description = "Must be 'calculateLoanSchedule'", required = true)
          final String commandParam,
      @Context final UriInfo uriInfo,
      final String apiRequestBodyAsJson) {

    this.dataValidator.validatePublicSimulationRequest(commandParam, apiRequestBodyAsJson);

    // Use LoanScheduleAssembler directly instead of LoanScheduleCalculationPlatformService.
    // The platform service calls LoanApplicationValidator.validateForCreate() which requires
    // clientId — a field that must not be present in public simulation requests.
    // The assembler is auth-free and does not enforce clientId.
    final JsonElement jsonElement = this.fromJsonHelper.parse(apiRequestBodyAsJson);

    final LoanScheduleModel loanSchedule =
        this.loanScheduleAssembler.assembleLoanScheduleFrom(jsonElement);

    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    return this.loanScheduleSerializer.serialize(settings, loanSchedule.toData(), new HashSet<>());
  }
}
