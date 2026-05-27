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
package org.apache.fineract.selfservice.office.service;

import java.util.Collection;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.apache.fineract.selfservice.office.data.OfficeDetailsData;
import org.apache.fineract.selfservice.office.data.OfficeGeolocationData;
import org.apache.fineract.selfservice.office.data.OfficeServiceData;
import org.apache.fineract.selfservice.office.data.SelfOfficeAddressData;

/**
 * Read-only service for retrieving office information scoped to the authenticated self-service
 * user's organizational hierarchy.
 */
public interface SelfServiceOfficeReadPlatformService {

  /**
   * Retrieves the id, name, and external id of the given office.
   *
   * @param officeId the office identifier
   * @return the office details
   * @throws OfficeNotFoundException if the office does not exist within the user's hierarchy
   */
  OfficeDetailsData retrieveOfficeDetails(Long officeId);

  /**
   * Retrieves the list of services offered by the given office.
   *
   * @param officeId the office identifier
   * @return a collection of services; empty if none are configured
   * @throws OfficeNotFoundException if the office does not exist within the user's hierarchy
   */
  Collection<OfficeServiceData> retrieveOfficeServices(Long officeId);

  /**
   * Retrieves the latitude and longitude of the given office.
   *
   * @param officeId the office identifier
   * @return the geolocation data, or {@code null} if no geolocation is recorded
   * @throws OfficeNotFoundException if the office does not exist within the user's hierarchy
   */
  OfficeGeolocationData retrieveOfficeGeolocation(Long officeId);

  /**
   * Retrieves the physical address of the given office.
   *
   * @param officeId the office identifier
   * @return the address data, or {@code null} if the address table is unavailable or no address
   *     exists
   * @throws OfficeNotFoundException if the office does not exist within the user's hierarchy
   */
  SelfOfficeAddressData retrieveOfficeAddress(Long officeId);

  /**
   * Indicates whether the {@code m_office_address} table exists in the current tenant database.
   *
   * @return {@code true} if the table is available; {@code false} otherwise
   */
  boolean isOfficeAddressTableAvailable();
}
