/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

/** Read platform service for retrieving office address-related data. */
public interface SelfServiceOfficeAddressReadService {

  /**
   * Retrieves the country name of the office address associated with the given client.
   *
   * <p>The lookup chain is: Client → Office → Office Address → Address → Country (CodeValue).
   * Returns an empty string if any link in the chain is missing or null.
   *
   * @param clientId the client identifier; may be {@code null}
   * @return the country name, or an empty string as fallback
   */
  String retrieveOfficeCountryByClientId(Long clientId);
}
