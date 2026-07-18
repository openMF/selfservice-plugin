/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
