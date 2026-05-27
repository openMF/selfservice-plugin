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
