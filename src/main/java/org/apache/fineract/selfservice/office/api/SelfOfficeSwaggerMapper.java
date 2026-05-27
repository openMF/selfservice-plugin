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

import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.config.MapstructMapperConfig;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.selfservice.office.data.OfficeDetailsData;
import org.apache.fineract.selfservice.office.data.OfficeGeolocationData;
import org.apache.fineract.selfservice.office.data.OfficeServiceData;
import org.apache.fineract.selfservice.office.data.SelfOfficeAddressData;
import org.mapstruct.Mapper;

/** MapStruct mapper for converting office domain objects to Swagger API response models. */
@Mapper(config = MapstructMapperConfig.class, componentModel = "spring")
public interface SelfOfficeSwaggerMapper {

  /** Maps a {@link CommandProcessingResult} to the PUT office response model. */
  SelfOfficesApiResourceSwagger.PutOfficesOfficeIdResponse toPutOfficesOfficeIdResponse(
      CommandProcessingResult commandProcessingResult);

  default SelfOfficesApiResourceSwagger.PutOfficesOfficeIdResponse.PutOfficesOfficeIdResponseChanges
      toPutOfficesOfficeIdResponseChanges(Map<String, Object> changes) {
    SelfOfficesApiResourceSwagger.PutOfficesOfficeIdResponse.PutOfficesOfficeIdResponseChanges
        response =
            new SelfOfficesApiResourceSwagger.PutOfficesOfficeIdResponse
                .PutOfficesOfficeIdResponseChanges();
    Optional.ofNullable(changes)
        .map(c -> c.get("name"))
        .ifPresent(c -> response.name = String.valueOf(c));
    return response;
  }

  /** Maps a core {@link OfficeData} to the GET office response model. */
  SelfOfficesApiResourceSwagger.GetOfficesResponse toGetOfficesResponse(OfficeData officeData);

  /** Maps an {@link OfficeDetailsData} to the GET office details response model. */
  SelfOfficesApiResourceSwagger.GetOfficeDetailsResponse toGetOfficeDetailsResponse(
      OfficeDetailsData data);

  /** Maps an {@link OfficeServiceData} to the GET office services response model. */
  SelfOfficesApiResourceSwagger.GetOfficeServicesResponse toGetOfficeServicesResponse(
      OfficeServiceData data);

  /** Maps an {@link OfficeGeolocationData} to the GET office geolocation response model. */
  SelfOfficesApiResourceSwagger.GetOfficeGeolocationResponse toGetOfficeGeolocationResponse(
      OfficeGeolocationData data);

  /** Maps a {@link SelfOfficeAddressData} to the GET office address response model. */
  SelfOfficesApiResourceSwagger.GetOfficeAddressResponse toGetOfficeAddressResponse(
      SelfOfficeAddressData data);
}
