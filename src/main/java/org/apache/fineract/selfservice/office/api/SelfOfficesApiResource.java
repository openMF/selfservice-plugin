/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.selfservice.office.data.OfficeDetailsData;
import org.apache.fineract.selfservice.office.data.OfficeGeolocationData;
import org.apache.fineract.selfservice.office.data.OfficeServiceData;
import org.apache.fineract.selfservice.office.data.SelfOfficeAddressData;
import org.apache.fineract.selfservice.office.service.SelfServiceOfficeReadPlatformService;
import org.springframework.stereotype.Component;

@Path("/v1/self/offices")
@Component
@Tag(
    name = "Offices",
    description =
        "Offices are used to model an MFIs structure. A hierarchical representation of offices is supported. There will always be at least one office (which represents the MFI or an MFIs head office). All subsequent offices added must have a parent office.")
@RequiredArgsConstructor
/**
 * JAX-RS resource exposing self-service office endpoints under {@code /v1/self/offices}.
 *
 * <p>Provides read-only access to office information for authenticated self-service users. All
 * endpoints enforce hierarchy-based security scoping so that a user can only query offices within
 * their own organizational hierarchy.
 */
public class SelfOfficesApiResource {

  /** The set of parameters that are supported in response for {@link OfficeData}. */
  private static final Set<String> RESPONSE_DATA_PARAMETERS =
      new HashSet<>(
          List.of(
              "id",
              "name",
              "nameDecorated",
              "externalId",
              "openingDate",
              "hierarchy",
              "parentId",
              "parentName",
              "allowedParents"));

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "OFFICE";

  private final SelfOfficeSwaggerMapper officeSwaggerMapper;
  private final PlatformSecurityContext context;
  private final OfficeReadPlatformService readPlatformService;
  private final DefaultToApiJsonSerializer<OfficeData> toApiJsonSerializer;
  private final ApiRequestParameterHelper apiRequestParameterHelper;
  private final SqlValidator sqlValidator;
  private final SelfServiceOfficeReadPlatformService selfServiceOfficeReadPlatformService;

  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Offices",
      description =
          "Example Requests:\n"
              + "\n"
              + "offices\n"
              + "\n"
              + "\n"
              + "offices?fields=id,name,openingDate")
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
                                  SelfOfficesApiResourceSwagger.GetOfficesResponse.class))))
  /**
   * Lists all offices visible to the authenticated self-service user.
   *
   * @param uriInfo JAX-RS URI context for query parameter processing
   * @param onlyManualEntries whether to include all offices or only manual entries
   * @param orderBy optional column name to sort results by
   * @param sortOrder optional sort direction ({@code ASC} or {@code DESC})
   * @return JSON array of office data
   */
  public String retrieveOffices(
      @Context final UriInfo uriInfo,
      @DefaultValue("false")
          @QueryParam("includeAllOffices")
          @Parameter(description = "includeAllOffices")
          final boolean onlyManualEntries,
      @QueryParam("orderBy") @Parameter(description = "orderBy") final String orderBy,
      @QueryParam("sortOrder") @Parameter(description = "sortOrder") final String sortOrder) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    sqlValidator.validate(orderBy);
    sqlValidator.validate(sortOrder);
    final SearchParameters searchParameters =
        SearchParameters.builder().orphansOnly(false).orderBy(orderBy).sortOrder(sortOrder).build();
    final Collection<OfficeData> offices =
        readPlatformService.retrieveAllOffices(onlyManualEntries, searchParameters);
    final ApiRequestJsonSerializationSettings settings =
        apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    return toApiJsonSerializer.serialize(settings, offices, RESPONSE_DATA_PARAMETERS);
  }

  @GET
  @Path("template")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Details Template",
      description =
          "This is a convenience resource. It can be useful when building maintenance user interface screens for client applications. The template data returned consists of any or all of:\n"
              + "\n"
              + "Field Defaults\n"
              + "Allowed description Lists\n"
              + "Example Request:\n"
              + "\n"
              + "offices/template")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfOfficesApiResourceSwagger.GetOfficesTemplateResponse.class)))
  /**
   * Retrieves the office creation template containing default fields and allowed parent offices.
   *
   * @param uriInfo JAX-RS URI context for query parameter processing
   * @return JSON representation of the office template
   */
  public String retrieveOfficeTemplate(@Context final UriInfo uriInfo) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    OfficeData office = readPlatformService.retrieveNewOfficeTemplate();
    final Collection<OfficeData> allowedParents =
        readPlatformService.retrieveAllOfficesForDropdown();
    office = OfficeData.appendedTemplate(office, allowedParents);
    final ApiRequestJsonSerializationSettings settings =
        apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    return toApiJsonSerializer.serialize(settings, office, RESPONSE_DATA_PARAMETERS);
  }

  @GET
  @Path("{officeId}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve an Office",
      description =
          "Example Requests:\n"
              + "\n"
              + "offices/1\n"
              + "\n"
              + "\n"
              + "offices/1?template=true\n"
              + "\n"
              + "\n"
              + "offices/1?fields=id,name,parentName")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(implementation = SelfOfficesApiResourceSwagger.GetOfficesResponse.class)))
  /**
   * Retrieves a single office by its database identifier.
   *
   * @param officeId the office identifier
   * @param uriInfo JAX-RS URI context for query parameter processing
   * @return JSON representation of the office
   */
  public String retrieveOffice(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @Context final UriInfo uriInfo) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final ApiRequestJsonSerializationSettings settings =
        apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    OfficeData office = readPlatformService.retrieveOffice(officeId);
    if (settings.isTemplate()) {
      final Collection<OfficeData> allowedParents =
          readPlatformService.retrieveAllowedParents(officeId);
      office = OfficeData.appendedTemplate(office, allowedParents);
    }
    return toApiJsonSerializer.serialize(settings, office, RESPONSE_DATA_PARAMETERS);
  }

  @GET
  @Path("/external-id/{externalId}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve an Office using external id",
      description =
          "Example Requests:\n"
              + "\n"
              + "offices/external-id/asd123\n"
              + "\n"
              + "\n"
              + "offices/external-id/asd123?template=true\n"
              + "\n"
              + "\n"
              + "offices/external-id/asd123?fields=id,name,parentName")
  /**
   * Retrieves a single office by its external identifier.
   *
   * @param externalId the external identifier assigned to the office
   * @param uriInfo JAX-RS URI context for query parameter processing
   * @return the office details mapped to the Swagger response model
   */
  public SelfOfficesApiResourceSwagger.GetOfficesResponse retrieveOfficeByExternalId(
      @PathParam("externalId") @Parameter(description = "externalId") final String externalId,
      @Context final UriInfo uriInfo) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final ApiRequestJsonSerializationSettings settings =
        apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    OfficeData office =
        readPlatformService.retrieveOfficeWithExternalId(ExternalIdFactory.produce(externalId));
    if (settings.isTemplate()) {
      final Collection<OfficeData> allowedParents =
          readPlatformService.retrieveAllowedParents(office.getId());
      office = OfficeData.appendedTemplate(office, allowedParents);
    }
    return officeSwaggerMapper.toGetOfficesResponse(office);
  }

  @GET
  @Path("{officeId}/details")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Details",
      description =
          "Returns the office id, name, and external id.\n\nExample Request:\n\nself/offices/1/details")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfOfficesApiResourceSwagger.GetOfficeDetailsResponse.class)))
  /**
   * Retrieves the id, name, and external id of a specific office.
   *
   * @param officeId the office identifier
   * @return JSON representation of the office details
   * @throws OfficeNotFoundException if the office does not exist or is outside the user's hierarchy
   */
  public String retrieveOfficeDetails(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    if (officeId == null || officeId <= 0) {
      throw new OfficeNotFoundException(officeId);
    }
    final OfficeDetailsData data =
        selfServiceOfficeReadPlatformService.retrieveOfficeDetails(officeId);
    return toApiJsonSerializer.serializeResult(data);
  }

  @GET
  @Path("{officeId}/services")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Services",
      description =
          "Returns a list of services offered by the office.\n\nExample Request:\n\nself/offices/1/services")
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
                                  SelfOfficesApiResourceSwagger.GetOfficeServicesResponse.class))))
  /**
   * Retrieves the list of services offered by a specific office.
   *
   * @param officeId the office identifier
   * @return JSON array of office service data
   * @throws OfficeNotFoundException if the office does not exist or is outside the user's hierarchy
   */
  public String retrieveOfficeServices(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    if (officeId == null || officeId <= 0) {
      throw new OfficeNotFoundException(officeId);
    }
    final Collection<OfficeServiceData> data =
        selfServiceOfficeReadPlatformService.retrieveOfficeServices(officeId);
    return toApiJsonSerializer.serializeResult(data);
  }

  @GET
  @Path("{officeId}/geolocation")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Geolocation",
      description =
          "Returns the latitude and longitude of the office.\n\nExample Request:\n\nself/offices/1/geolocation")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfOfficesApiResourceSwagger.GetOfficeGeolocationResponse.class)))
  /**
   * Retrieves the latitude and longitude of a specific office.
   *
   * @param officeId the office identifier
   * @return JSON representation of the geolocation, or {@code null} if no geolocation is recorded
   * @throws OfficeNotFoundException if the office does not exist or is outside the user's hierarchy
   */
  public String retrieveOfficeGeolocation(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    if (officeId == null || officeId <= 0) {
      throw new OfficeNotFoundException(officeId);
    }
    final OfficeGeolocationData data =
        selfServiceOfficeReadPlatformService.retrieveOfficeGeolocation(officeId);
    return toApiJsonSerializer.serializeResult(data);
  }

  @GET
  @Path("{officeId}/address")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Address",
      description =
          "Returns the street, postal code, municipality, state, and country of the office.\n\nExample Request:\n\nself/offices/1/address")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              schema =
                  @Schema(
                      implementation =
                          SelfOfficesApiResourceSwagger.GetOfficeAddressResponse.class)))
  /**
   * Retrieves the physical address of a specific office.
   *
   * @param officeId the office identifier
   * @return JSON representation of the address, or {@code null} if unavailable
   * @throws OfficeNotFoundException if the office does not exist or is outside the user's hierarchy
   */
  public String retrieveOfficeAddress(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    if (officeId == null || officeId <= 0) {
      throw new OfficeNotFoundException(officeId);
    }
    final SelfOfficeAddressData data =
        selfServiceOfficeReadPlatformService.retrieveOfficeAddress(officeId);
    return toApiJsonSerializer.serializeResult(data);
  }
}
