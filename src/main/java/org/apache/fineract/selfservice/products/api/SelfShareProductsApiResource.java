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
package org.apache.fineract.selfservice.products.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.portfolio.products.data.ProductData;
import org.apache.fineract.portfolio.products.service.ShareProductReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/products/share")
@Component
@Tag(name = "Self Share Products", description = "")
@RequiredArgsConstructor
public class SelfShareProductsApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final ShareProductReadPlatformService shareProductReadPlatformService;
  private final DefaultToApiJsonSerializer<ProductData> toApiJsonSerializer;
  private final ApiRequestParameterHelper apiRequestParameterHelper;

  @GET
  @Path("{productId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve a Share Product",
      description = "Retrieves a share product by its identifier.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Not Found")
  })
  public String retrieveProduct(
      @PathParam("productId") final Long productId, @Context final UriInfo uriInfo) {

    this.context.authenticatedSelfServiceUser();
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    final ProductData productData =
        this.shareProductReadPlatformService.retrieveOne(productId, false);
    return this.toApiJsonSerializer.serialize(settings, productData);
  }

  @GET
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Share Products",
      description = "Lists all available share products with optional pagination.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "OK"),
    @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  public String retrieveAllProducts(
      @QueryParam("offset") final Integer offset,
      @QueryParam("limit") final Integer limit,
      @Context final UriInfo uriInfo) {

    this.context.authenticatedSelfServiceUser();
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    final Page<ProductData> products =
        this.shareProductReadPlatformService.retrieveAllProducts(offset, limit);
    return this.toApiJsonSerializer.serialize(settings, products);
  }
}
