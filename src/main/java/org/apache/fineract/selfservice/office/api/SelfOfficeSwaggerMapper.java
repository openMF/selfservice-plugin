/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
