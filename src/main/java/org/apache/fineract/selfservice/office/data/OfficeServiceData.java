/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.office.data;

/** Immutable data transfer object representing a service offered by an office. */
public final class OfficeServiceData {

  private final Long serviceId;
  private final String serviceName;
  private final String serviceExternalId;
  private final String serviceWorkingHours;

  private OfficeServiceData(
      final Long serviceId,
      final String serviceName,
      final String serviceExternalId,
      final String serviceWorkingHours) {
    this.serviceId = serviceId;
    this.serviceName = serviceName;
    this.serviceExternalId = serviceExternalId;
    this.serviceWorkingHours = serviceWorkingHours;
  }

  /**
   * Creates a new instance.
   *
   * @param serviceId the service identifier
   * @param serviceName the human-readable service name
   * @param serviceExternalId the external identifier for the service
   * @param serviceWorkingHours the working hours description
   * @return a new {@code OfficeServiceData}
   */
  public static OfficeServiceData instance(
      final Long serviceId,
      final String serviceName,
      final String serviceExternalId,
      final String serviceWorkingHours) {
    return new OfficeServiceData(serviceId, serviceName, serviceExternalId, serviceWorkingHours);
  }

  public Long getServiceId() {
    return serviceId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public String getServiceExternalId() {
    return serviceExternalId;
  }

  public String getServiceWorkingHours() {
    return serviceWorkingHours;
  }
}
