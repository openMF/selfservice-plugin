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

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.data.SavingsProductData;
import org.apache.fineract.portfolio.savings.service.SavingsProductReadPlatformService;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v1/self/savingsproducts")
@Component
@Tag(name = "Self Savings Products", description = "")
@RequiredArgsConstructor
public class SelfSavingsProductsApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final SavingsProductReadPlatformService savingsProductReadPlatformService;
  private final DefaultToApiJsonSerializer<SavingsProductData> toApiJsonSerializer;
  private final ApiRequestParameterHelper apiRequestParameterHelper;

  @GET
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String retrieveAll(@Context final UriInfo uriInfo) {

    this.context.validateHasReadPermission("SAVINGSPRODUCT");
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    final Collection<SavingsProductData> products =
        this.savingsProductReadPlatformService.retrieveAll();
    return this.toApiJsonSerializer.serialize(settings, products);
  }

  @GET
  @Path("{productId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public String retrieveOne(
      @PathParam(SavingsApiConstants.productIdParamName) final Long productId,
      @Context final UriInfo uriInfo) {

    this.context.validateHasReadPermission("SAVINGSPRODUCT");
    final ApiRequestJsonSerializationSettings settings =
        this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
    final SavingsProductData savingsProduct =
        this.savingsProductReadPlatformService.retrieveOne(productId);
    return this.toApiJsonSerializer.serialize(settings, savingsProduct);
  }
}
