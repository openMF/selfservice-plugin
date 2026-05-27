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

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.apache.fineract.selfservice.office.data.OfficeDetailsData;
import org.apache.fineract.selfservice.office.data.OfficeGeolocationData;
import org.apache.fineract.selfservice.office.data.OfficeServiceData;
import org.apache.fineract.selfservice.office.data.SelfOfficeAddressData;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed implementation of {@link SelfServiceOfficeReadPlatformService} that enforces
 * hierarchy-based security scoping on every query.
 */
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SelfServiceOfficeReadPlatformServiceImpl
    implements SelfServiceOfficeReadPlatformService {

  private final JdbcTemplate jdbcTemplate;
  private final PlatformSelfServiceSecurityContext context;

  private volatile boolean officeAddressTableAvailable;

  @PostConstruct
  void checkTableAvailability() {
    try {
      jdbcTemplate.execute(
          (org.springframework.jdbc.core.ConnectionCallback<Void>)
              conn -> {
                try (ResultSet rs =
                    conn.getMetaData().getTables(null, null, "m_office_address", null)) {
                  officeAddressTableAvailable = rs.next();
                }
                return null;
              });
    } catch (Exception e) {
      log.warn(
          "Failed to detect m_office_address table availability; address endpoint disabled", e);
      officeAddressTableAvailable = false;
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean isOfficeAddressTableAvailable() {
    return officeAddressTableAvailable;
  }

  private String getHierarchySearchString() {
    final AppSelfServiceUser currentUser = this.context.authenticatedSelfServiceUser();
    return currentUser.getOffice().getHierarchy() + "%";
  }

  private void validateOfficeExistsInHierarchy(final Long officeId) {
    final String hierarchySearchString = getHierarchySearchString();
    try {
      this.jdbcTemplate.queryForObject(
          "SELECT 1 FROM m_office o WHERE o.id = ? AND o.hierarchy LIKE ?",
          Integer.class,
          officeId,
          hierarchySearchString);
    } catch (final EmptyResultDataAccessException e) {
      throw new OfficeNotFoundException(officeId, e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public OfficeDetailsData retrieveOfficeDetails(final Long officeId) {
    this.context.authenticatedSelfServiceUser();
    validateOfficeExistsInHierarchy(officeId);
    final String hierarchySearchString = getHierarchySearchString();
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT o.id, o.name, o.external_id FROM m_office o"
              + " WHERE o.id = ? AND o.hierarchy LIKE ?",
          new OfficeDetailsRowMapper(),
          officeId,
          hierarchySearchString);
    } catch (final EmptyResultDataAccessException e) {
      throw new OfficeNotFoundException(officeId, e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public Collection<OfficeServiceData> retrieveOfficeServices(final Long officeId) {
    this.context.authenticatedSelfServiceUser();
    validateOfficeExistsInHierarchy(officeId);
    return this.jdbcTemplate.query(
        "SELECT s.id, s.service_name, s.service_external_id, s.working_hours"
            + " FROM m_selfservice_office_service s"
            + " JOIN m_office o ON s.office_id = o.id"
            + " WHERE s.office_id = ? AND o.hierarchy LIKE ?",
        new OfficeServiceRowMapper(),
        officeId,
        getHierarchySearchString());
  }

  /** {@inheritDoc} */
  @Override
  public OfficeGeolocationData retrieveOfficeGeolocation(final Long officeId) {
    this.context.authenticatedSelfServiceUser();
    validateOfficeExistsInHierarchy(officeId);
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT g.latitude, g.longitude"
              + " FROM m_selfservice_office_geolocation g"
              + " JOIN m_office o ON g.office_id = o.id"
              + " WHERE g.office_id = ? AND o.hierarchy LIKE ?",
          new OfficeGeolocationRowMapper(),
          officeId,
          getHierarchySearchString());
    } catch (final EmptyResultDataAccessException e) {
      return null;
    }
  }

  /** {@inheritDoc} */
  @Override
  public SelfOfficeAddressData retrieveOfficeAddress(final Long officeId) {
    this.context.authenticatedSelfServiceUser();
    validateOfficeExistsInHierarchy(officeId);
    if (!officeAddressTableAvailable) {
      return null;
    }
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT a.street, a.postal_code, a.city, a.state_province, cv.code_value AS country_name"
              + " FROM m_office_address oa"
              + " JOIN m_address a ON oa.address_id = a.id"
              + " JOIN m_office o ON oa.office_id = o.id"
              + " LEFT JOIN m_code_value cv ON a.country_id = cv.id"
              + " WHERE oa.office_id = ? AND o.hierarchy LIKE ? AND oa.is_active = true",
          new OfficeAddressRowMapper(),
          officeId,
          getHierarchySearchString());
    } catch (final EmptyResultDataAccessException e) {
      return null;
    }
  }

  private static final class OfficeDetailsRowMapper implements RowMapper<OfficeDetailsData> {

    @Override
    public OfficeDetailsData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      final Long id = rs.getLong("id");
      final String name = rs.getString("name");
      final String externalId = rs.getString("external_id");
      return OfficeDetailsData.instance(id, name, externalId);
    }
  }

  private static final class OfficeServiceRowMapper implements RowMapper<OfficeServiceData> {

    @Override
    public OfficeServiceData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      final Long serviceId = rs.getLong("id");
      final String serviceName = rs.getString("service_name");
      final String serviceExternalId = rs.getString("service_external_id");
      final String workingHours = rs.getString("working_hours");
      return OfficeServiceData.instance(serviceId, serviceName, serviceExternalId, workingHours);
    }
  }

  private static final class OfficeGeolocationRowMapper
      implements RowMapper<OfficeGeolocationData> {

    @Override
    public OfficeGeolocationData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      final BigDecimal latitude = rs.getBigDecimal("latitude");
      final BigDecimal longitude = rs.getBigDecimal("longitude");
      return OfficeGeolocationData.instance(latitude, longitude);
    }
  }

  private static final class OfficeAddressRowMapper implements RowMapper<SelfOfficeAddressData> {

    @Override
    public SelfOfficeAddressData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      final String street = rs.getString("street");
      final String postalCode = rs.getString("postal_code");
      final String city = rs.getString("city");
      final String stateProvince = rs.getString("state_province");
      final String countryName = rs.getString("country_name");
      return SelfOfficeAddressData.instance(street, postalCode, city, stateProvince, countryName);
    }
  }
}
