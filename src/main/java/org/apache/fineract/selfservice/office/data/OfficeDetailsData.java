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

/** Immutable data transfer object representing core office identification fields. */
public final class OfficeDetailsData {

  private final Long id;
  private final String name;
  private final String externalId;

  private OfficeDetailsData(final Long id, final String name, final String externalId) {
    this.id = id;
    this.name = name;
    this.externalId = externalId;
  }

  /**
   * Creates a new instance.
   *
   * @param id the office identifier
   * @param name the office name
   * @param externalId the external identifier assigned to the office
   * @return a new {@code OfficeDetailsData}
   */
  public static OfficeDetailsData instance(
      final Long id, final String name, final String externalId) {
    return new OfficeDetailsData(id, name, externalId);
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getExternalId() {
    return externalId;
  }
}
