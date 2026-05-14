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
package org.apache.fineract.infrastructure.configuration.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.data.ExternalServicesPropertiesData;
import org.apache.fineract.infrastructure.configuration.data.NationalIdCredentialsData;
import org.apache.fineract.infrastructure.configuration.data.NotificationCredentialsData;
import org.apache.fineract.infrastructure.configuration.exception.ExternalServiceConfigurationNotFoundException;
import org.apache.fineract.infrastructure.core.service.StringUtil;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExternalApiRestServicesPropertiesReadPlatformServiceImpl
    implements ExternalApiRestServicesPropertiesReadPlatformService {

  private final JdbcTemplate jdbcTemplate;

  private static final class NationalIdCredentialsDataExtractor
      implements ResultSetExtractor<NationalIdCredentialsData> {

    @Override
    public NationalIdCredentialsData extractData(final ResultSet rs)
        throws SQLException, DataAccessException {
      String host = null;
      String header = null;
      String headerValue = null;
      String httpMethod = null;
      String parameterName = null;
      Boolean isEnabled = null;
      while (rs.next()) {
        if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_HOST)) {
          host = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_HEADER)) {
          header = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_HEADER_VALUE)) {
          headerValue = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_HTTP_METHOD)) {
          httpMethod = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_PARAMETER_NAME)) {
          parameterName = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_IS_ENABLED)) {
          isEnabled = rs.getBoolean("value");
        }
      }
      return new NationalIdCredentialsData()
          .setHost(host)
          .setHeader(header)
          .setHeaderValue(headerValue)
          .setHttpMethod(httpMethod)
          .setParameterName(parameterName)
          .setEnabled(isEnabled);
    }
  }

  private static final class NotificationCredentialsDataExtractor
      implements ResultSetExtractor<NotificationCredentialsData> {

    @Override
    public NotificationCredentialsData extractData(final ResultSet rs)
        throws SQLException, DataAccessException {
      String host = null;
      String header = null;
      String headerValue = null;
      String httpMethod = null;
      Boolean isEmail = null;
      Boolean isSms = null;
      Boolean isWhatsapp = null;
      Boolean isEnabled = null;
      while (rs.next()) {
        if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_HOST)) {
          host = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_HEADER)) {
          header = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_HEADER_VALUE)) {
          headerValue = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_HTTP_METHOD)) {
          httpMethod = rs.getString("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_IS_EMAIL)) {
          isEmail = rs.getBoolean("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_IS_SMS)) {
          isSms = rs.getBoolean("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NOTIFICATION_IS_WHATSAPP)) {
          isWhatsapp = rs.getBoolean("value");
        } else if (rs.getString("name")
            .equalsIgnoreCase(ExternalApiRestServicesConstants.NATIONAL_ID_IS_ENABLED)) {
          isEnabled = rs.getBoolean("value");
        }
      }
      return new NotificationCredentialsData()
          .setHost(host)
          .setHeader(header)
          .setHeaderValue(headerValue)
          .setHttpMethod(httpMethod)
          .setEmail(isEmail)
          .setSms(isSms)
          .setWhatsapp(isWhatsapp)
          .setEnabled(isEnabled);
    }
  }

  private static final class ExternalServiceMapper
      implements RowMapper<ExternalServicesPropertiesData> {

    List<String> secretAttributes;

    ExternalServiceMapper() {
      secretAttributes = new ArrayList<>();
      secretAttributes.add("password");
      secretAttributes.add("server_key");
      secretAttributes.add("headerValue");
    }

    @Override
    public ExternalServicesPropertiesData mapRow(
        ResultSet rs, @SuppressWarnings("unused") int rowNum) throws SQLException {
      final String name = rs.getString("name");
      String value = rs.getString("value");
      // Masking the password as we should not send the password back
      if (name != null && secretAttributes.contains(name)) {
        value = StringUtil.maskValue(value);
      }
      return new ExternalServicesPropertiesData().setName(name).setValue(value);
    }
  }

  @Override
  public NationalIdCredentialsData getNationalIdCredentials() {
    final ResultSetExtractor<NationalIdCredentialsData> resultSetExtractor =
        new NationalIdCredentialsDataExtractor();
    final String sql =
        "SELECT esp.name, esp.value FROM c_external_service_properties esp inner join c_external_service es on esp.external_service_id = es.id where es.name = '"
            + ExternalApiRestServicesConstants.NATIONAL_ID_SERVICE_NAME
            + "'";
    final NationalIdCredentialsData NationalIdCredentialsData =
        this.jdbcTemplate.query(sql, resultSetExtractor, new Object[] {});
    return NationalIdCredentialsData;
  }

  @Override
  public NotificationCredentialsData getNotificationCredentials() {
    // TODO Auto-generated method stub
    final ResultSetExtractor<NotificationCredentialsData> resultSetExtractor =
        new NotificationCredentialsDataExtractor();
    final String sql =
        "SELECT esp.name, esp.value FROM c_external_service_properties esp inner join c_external_service es on esp.external_service_id = es.id where es.name = '"
            + ExternalApiRestServicesConstants.NOTIFICATION_SERVICE_NAME
            + "'";
    final NotificationCredentialsData smtpCredentialsData =
        this.jdbcTemplate.query(sql, resultSetExtractor, new Object[] {});
    return smtpCredentialsData;
  }

  @Override
  public Collection<ExternalServicesPropertiesData> retrieveOne(String serviceName) {
    String serviceNameToUse = null;
    switch (serviceName) {
      case "NotificationsService":
        serviceNameToUse = ExternalApiRestServicesConstants.NOTIFICATION_SERVICE_NAME;
        break;

      case "NationalIdService":
        serviceNameToUse = ExternalApiRestServicesConstants.NATIONAL_ID_SERVICE_NAME;
        break;

      default:
        throw new ExternalServiceConfigurationNotFoundException(serviceName);
    }
    final ExternalServiceMapper mapper = new ExternalServiceMapper();
    final String sql =
        "SELECT esp.name, esp.value FROM c_external_service_properties esp inner join c_external_service es on esp.external_service_id = es.id where es.name = '"
            + serviceNameToUse
            + "'";
    return this.jdbcTemplate.query(sql, mapper); // NOSONAR
  }
}
