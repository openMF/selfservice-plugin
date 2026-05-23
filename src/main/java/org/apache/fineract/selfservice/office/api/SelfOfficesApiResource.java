/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
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
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.springframework.stereotype.Component;

@Path("/v1/self/offices")
@Component
@Tag(name = "Offices", description = "Offices are used to model an MFIs structure. A hierarchical representation of offices is supported. There will always be at least one office (which represents the MFI or an MFIs head office). All subsequent offices added must have a parent office.")
@RequiredArgsConstructor
public class SelfOfficesApiResource {

    /**
     * The set of parameters that are supported in response for {@link OfficeData}.
     */
    private static final Set<String> RESPONSE_DATA_PARAMETERS = new HashSet<>(
            List.of("id", "name", "nameDecorated", "externalId", "openingDate", "hierarchy", "parentId", "parentName", "allowedParents"));

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "OFFICE";

    private final SelfOfficeSwaggerMapper officeSwaggerMapper;
    private final PlatformSecurityContext context;
    private final OfficeReadPlatformService readPlatformService;
    private final DefaultToApiJsonSerializer<OfficeData> toApiJsonSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final SqlValidator sqlValidator;

    @GET
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "List Offices", description = "Example Requests:\n" + "\n" + "offices\n" + "\n" + "\n"
            + "offices?fields=id,name,openingDate")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SelfOfficesApiResourceSwagger.GetOfficesResponse.class))))
    public String retrieveOffices(@Context final UriInfo uriInfo,
            @DefaultValue("false") @QueryParam("includeAllOffices") @Parameter(description = "includeAllOffices") final boolean onlyManualEntries,
            @QueryParam("orderBy") @Parameter(description = "orderBy") final String orderBy,
            @QueryParam("sortOrder") @Parameter(description = "sortOrder") final String sortOrder) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        sqlValidator.validate(orderBy);
        sqlValidator.validate(sortOrder);
        final SearchParameters searchParameters = SearchParameters.builder().orphansOnly(false).orderBy(orderBy).sortOrder(sortOrder)
                .build();
        final Collection<OfficeData> offices = readPlatformService.retrieveAllOffices(onlyManualEntries, searchParameters);
        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return toApiJsonSerializer.serialize(settings, offices, RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("template")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve Office Details Template", description = "This is a convenience resource. It can be useful when building maintenance user interface screens for client applications. The template data returned consists of any or all of:\n"
            + "\n" + "Field Defaults\n" + "Allowed description Lists\n" + "Example Request:\n" + "\n" + "offices/template")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = SelfOfficesApiResourceSwagger.GetOfficesTemplateResponse.class)))
    public String retrieveOfficeTemplate(@Context final UriInfo uriInfo) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        OfficeData office = readPlatformService.retrieveNewOfficeTemplate();
        final Collection<OfficeData> allowedParents = readPlatformService.retrieveAllOfficesForDropdown();
        office = OfficeData.appendedTemplate(office, allowedParents);
        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return toApiJsonSerializer.serialize(settings, office, RESPONSE_DATA_PARAMETERS);
    }

    
    @GET
    @Path("{officeId}")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve an Office", description = "Example Requests:\n" + "\n" + "offices/1\n" + "\n" + "\n"
            + "offices/1?template=true\n" + "\n" + "\n" + "offices/1?fields=id,name,parentName")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = SelfOfficesApiResourceSwagger.GetOfficesResponse.class)))
    public String retrieveOffice(@PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
            @Context final UriInfo uriInfo) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        OfficeData office = readPlatformService.retrieveOffice(officeId);
        if (settings.isTemplate()) {
            final Collection<OfficeData> allowedParents = readPlatformService.retrieveAllowedParents(officeId);
            office = OfficeData.appendedTemplate(office, allowedParents);
        }
        return toApiJsonSerializer.serialize(settings, office, RESPONSE_DATA_PARAMETERS);
    }

    @GET
    @Path("/external-id/{externalId}")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve an Office using external id", description = "Example Requests:\n" + "\n" + "offices/external-id/asd123\n"
            + "\n" + "\n" + "offices/external-id/asd123?template=true\n" + "\n" + "\n"
            + "offices/external-id/asd123?fields=id,name,parentName")
    public SelfOfficesApiResourceSwagger.GetOfficesResponse retrieveOfficeByExternalId(
            @PathParam("externalId") @Parameter(description = "externalId") final String externalId, @Context final UriInfo uriInfo) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        OfficeData office = readPlatformService.retrieveOfficeWithExternalId(ExternalIdFactory.produce(externalId));
        if (settings.isTemplate()) {
            final Collection<OfficeData> allowedParents = readPlatformService.retrieveAllowedParents(office.getId());
            office = OfficeData.appendedTemplate(office, allowedParents);
        }
        return officeSwaggerMapper.toGetOfficesResponse(office);
    }

    
}
